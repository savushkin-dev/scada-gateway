package com.scada.gateway.pac;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;

import com.scada.gateway.opcua.ValueCodec;

/**
 * Работа с Lua-стейтом PAC. Ответы контроллера приходят как Lua-скрипт: driver-master
 * исполняет его и читает значения как Lua-переменные. Здесь то же самое на LuaJ.
 *
 * <p>Снимок GET_DEVICES_STATES у ptusa — таблица {@code t} по имени прибора:
 * <pre>
 *   t={ LINE1V0={M=0, ST=1}, LINE1M1={M=0, ST=0, FRQ=12.5, RPM=750, ...}, ... }
 *   t.OBJECT1={CMD=0, CUR_REC='Танк №1', RT_PAR_F={0, 0, 1, ...}, PAR_MAIN={1, 0.20, ...}}
 *   t.SYSTEM={P_V_OFF_DELAY_TIME=1000, ...}
 * </pre>
 * Значение канала — {@code t[deviceName][fieldName]}; поле-массив адресуется как
 * {@code RT_PAR_F[12]} (Lua-индекс с 1), хвост после {@code ]} — подпись канала
 * ({@code PAR_MAIN[1].P_CZAD_S}) и в адрес не входит.
 */
public final class PacLua {

    /** Время на исполнение одного ответа контроллера. Разбор снимка — миллисекунды. */
    public static final long TIME_BUDGET_MS = 1000;
    /** Проверка времени — раз в столько инструкций VM. */
    private static final int HOOK_EVERY = 1000;
    /** Потолок длины строки из string.rep (иначе одна инструкция съест память JVM). */
    private static final int MAX_STRING = 1 << 20;

    /**
     * Глобальные функции, которых снимку не нужно (он — только присваивания таблиц и литералы):
     * {@code load}/{@code loadstring}/{@code string.dump} — байткод Lua 5.1/5.2 не проверяется, и
     * собранный скриптом байткод — выход из песочницы; {@code pcall}/{@code xpcall} — перехват
     * ошибки лимита времени и продолжение цикла; файлы, модули, GC и окружения.
     */
    private static final String[] REMOVED = {
            "debug", "require", "package", "module", "dofile", "loadfile", "load", "loadstring",
            "pcall", "xpcall", "collectgarbage", "getfenv", "setfenv"};

    private PacLua() {
        // Утилитный класс — не инстанцируем.
    }

    /** Ответ контроллера исполнялся дольше лимита. Error, а не LuaError: скрипт его не перехватит. */
    static final class Timeout extends Error {
        Timeout(String message) {
            super(message, null, false, false);
        }
    }

    /** Lua-стейт с крайним сроком исполнения текущего скрипта (проверяет hook). */
    static final class LimitedGlobals extends Globals {
        volatile long deadlineNanos = Long.MAX_VALUE;
    }

    /**
     * Новый независимый Lua-стейт (на соединение). Скрипт присылает контроллер — то есть любой,
     * кто ответит на порту PAC, — поэтому стейт урезан до чистых вычислений: без io/os/luajava,
     * файлов, модулей, загрузки кода (в том числе байткода) и {@code pcall}; с лимитом времени на
     * скрипт ({@link #TIME_BUDGET_MS}) и потолком {@code string.rep}. Размер ответа до исполнения
     * ограничен в {@link PacProtocol#inflate}.
     *
     * <p>Памяти у LuaJ ограничить нельзя: раздувание таблицами упирается в лимит времени, а
     * рост строк — в потолок {@code string.rep}.
     */
    public static Globals newState() {
        LimitedGlobals globals = new LimitedGlobals();
        globals.load(new JseBaseLib());
        // PackageLib нужен остальным библиотекам при регистрации; сам require убираем ниже.
        globals.load(new PackageLib());
        globals.load(new TableLib());
        globals.load(new StringLib());
        globals.load(new JseMathLib());
        globals.load(new DebugLib());
        LoadState.install(globals);
        LuaC.install(globals);
        LuaValue sethook = globals.get("debug").get("sethook");
        for (String name : REMOVED) {
            globals.set(name, LuaValue.NIL);
        }
        LuaTable string = (LuaTable) globals.get("string");
        string.set("dump", LuaValue.NIL);
        LuaValue rep = string.get("rep");
        string.set("rep", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                long len = (long) args.checkjstring(1).length() * Math.max(0, args.checkint(2));
                if (len > MAX_STRING) throw new LuaError("string.rep: строка длиннее " + MAX_STRING + " символов");
                return rep.invoke(args);
            }
        });
        // Перехват по счётчику инструкций: не уложился в срок — обрыв (Error, не перехватывается).
        sethook.invoke(LuaValue.varargsOf(new LuaValue[]{new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                if (System.nanoTime() > globals.deadlineNanos) {
                    throw new Timeout("ответ PAC исполняется дольше " + TIME_BUDGET_MS + " мс");
                }
                return NIL;
            }
        }, LuaValue.valueOf(""), LuaValue.valueOf(HOOK_EVERY)}));
        return globals;
    }

    /**
     * Исполнить Lua-скрипт в стейте (наполняет глобальные переменные/таблицы) не дольше
     * {@link #TIME_BUDGET_MS}. Превышение лимита или ошибка скрипта — {@link LuaError}: вызывающий
     * (PacClientService) рвёт соединение, и опрос переподключается.
     */
    public static void exec(Globals globals, String script) {
        exec(globals, script, TIME_BUDGET_MS);
    }

    static void exec(Globals globals, String script, long budgetMs) {
        LimitedGlobals limited = (LimitedGlobals) globals;
        limited.deadlineNanos = System.nanoTime() + budgetMs * 1_000_000;
        try {
            globals.load(script).call();
        } catch (Timeout t) {
            throw new LuaError(t.getMessage());
        } finally {
            limited.deadlineNanos = Long.MAX_VALUE;
        }
    }

    /** protocol_version из ответа GET_INFO_ON_CONNECT (0, если не задан). */
    public static int protocolVersion(Globals globals) {
        return globals.get("protocol_version").optint(0);
    }

    /**
     * Значение поля прибора из снимка {@code t}, приведённое к dataType. null — если нет
     * снимка, прибора, поля или элемента массива, либо значение не приводится к типу.
     */
    public static Object read(Globals globals, String device, String field, String dataType) {
        if (device == null || field == null) return null;
        LuaValue snapshot = globals.get("t");
        if (!snapshot.istable()) return null;
        LuaValue dev = snapshot.get(device);
        if (!dev.istable()) return null;
        return convert(fieldValue(dev, field), dataType);
    }

    /** Поле прибора: {@code ST} или элемент массива {@code RT_PAR_F[12]} (подпись после ] отбрасывается). */
    private static LuaValue fieldValue(LuaValue dev, String field) {
        int lb = field.indexOf('[');
        if (lb < 0) return dev.get(field);
        int rb = field.indexOf(']', lb);
        if (rb < 0) return LuaValue.NIL;
        LuaValue array = dev.get(field.substring(0, lb));
        if (!array.istable()) return LuaValue.NIL;
        try {
            return array.get(Integer.parseInt(field.substring(lb + 1, rb).trim()));
        } catch (NumberFormatException e) {
            return LuaValue.NIL;
        }
    }

    private static Object convert(LuaValue v, String dataType) {
        if (v.isnil()) return null;
        if (dataType != null && dataType.trim().toUpperCase().startsWith("STRING")) return v.tojstring();
        if (!v.isnumber()) return null;
        if (ValueCodec.isBool(dataType)) return v.toint() != 0;
        if (ValueCodec.isInt(dataType)) return (long) v.todouble();
        return v.todouble();
    }

    /** Скалярное значение для set_cmd: boolean→1/0, число→как есть. */
    public static String scalar(Object value) {
        if (value instanceof Boolean) return ((Boolean) value) ? "1" : "0";
        return String.valueOf(value);
    }
}
