package com.scada.gateway.script;

import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.opcua.ValueCodec;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Один скрипт из привязки {@code scripts.yaml}: свой Lua-стейт (песочница), параметры,
 * маски каналов и состояние {@code ctx.state} по каждому каналу.
 *
 * <p>Контракт скрипта:
 * <pre>
 *   function process(value, quality, ctx)   -- обязательна
 *     return value, quality                 -- quality можно не возвращать (останется прежним)
 *   end
 *   function write(value, ctx)              -- необязательна: команда оператора → значение ПЛК
 *     return value
 *   end
 * </pre>
 * {@code ctx}: tag, device, field, device_type, data_type, unit, params (из YAML), state
 * (таблица канала, живёт между вызовами), timestamp (epoch мс).
 *
 * <p>Песочница: только base/string/table/math без файлов, модулей, загрузки кода, {@code pcall}
 * и управления GC; лимит времени на вызов (перехват каждые {@value #HOOK_EVERY} инструкций
 * бросает {@link Timeout} — это {@link Error}, скрипт его не перехватит); {@code string.rep}
 * не длиннее {@value #MAX_STRING} символов. Вызовы сериализуются — LuaJ-стейт не
 * потокобезопасен, а скрипт вызывают потоки опроса разных контроллеров.
 */
final class BoundScript {

    /** Проверка времени — раз в столько инструкций VM. */
    static final int HOOK_EVERY = 1000;
    /** Потолок длины строки из string.rep (иначе одна инструкция съест память JVM). */
    static final int MAX_STRING = 1 << 20;

    private static final String[] REMOVED_GLOBALS = {
            "debug", "require", "package", "module", "dofile", "loadfile", "load", "loadstring",
            "pcall", "xpcall", "collectgarbage", "getfenv", "setfenv"};
    private static final Set<String> QUALITIES = Set.of("GOOD", "BAD");

    /** Скрипт превысил лимит времени. Error, а не исключение Lua: не ловится изнутри скрипта. */
    static final class Timeout extends Error {
        Timeout(String message) {
            super(message, null, false, false);
        }
    }

    /** Ошибка скрипта с понятным текстом (для лога, журнала, /api/scripts). */
    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    final String file;
    final List<TagGlob> globs;
    private final long timeoutNanos;
    private final Globals globals;
    private final LuaFunction process;
    private final LuaFunction write;
    private final LuaTable params;
    private final Map<String, LuaTable> ctxByTag = new HashMap<>();
    private volatile long deadline;

    BoundScript(String file, String source, List<TagGlob> globs, Map<String, Object> params, Duration timeout)
            throws Failure {
        this.file = file;
        this.globs = globs;
        this.timeoutNanos = timeout.toNanos();
        this.globals = sandbox();
        this.params = (LuaTable) toLuaDeep(params == null ? Map.of() : params);
        try {
            deadline = System.nanoTime() + timeoutNanos;
            globals.load(source, "@" + file).call();
        } catch (LuaError | Timeout e) {
            throw new Failure(file + ": " + message(e));
        }
        LuaValue p = globals.get("process");
        if (!p.isfunction()) throw new Failure(file + ": нет функции process(value, quality, ctx)");
        this.process = (LuaFunction) p;
        LuaValue w = globals.get("write");
        this.write = w.isfunction() ? (LuaFunction) w : null;
    }

    boolean matches(String tagName) {
        for (TagGlob g : globs) if (g.matches(tagName)) return true;
        return false;
    }

    boolean hasWrite() {
        return write != null;
    }

    /** process(value, quality, ctx) → (значение, качество) с типом значения по dataType канала. */
    synchronized ValueScripts.Processed process(TagEntity tag, Object value, String quality, Instant ts)
            throws Failure {
        LuaTable ctx = ctx(tag);
        ctx.set("timestamp", LuaValue.valueOf((double) ts.toEpochMilli()));
        Varargs r;
        try {
            deadline = System.nanoTime() + timeoutNanos;
            r = process.invoke(LuaValue.varargsOf(toLua(value), LuaValue.valueOf(quality), ctx));
        } catch (LuaError | Timeout e) {
            throw new Failure(message(e));
        }
        Object out = toJava(r.arg1(), value, tag.getDataType());
        String q = quality;
        if (!r.arg(2).isnil()) {
            q = r.arg(2).tojstring().toUpperCase();
            if (!QUALITIES.contains(q)) throw new Failure("качество должно быть GOOD или BAD, а не " + r.arg(2));
        }
        return new ValueScripts.Processed(out, out == null ? "BAD" : q);
    }

    /** write(value, ctx) → значение для ПЛК (без write — как есть). */
    synchronized Object toPlc(TagEntity tag, Object value) throws Failure {
        if (write == null) return value;
        LuaValue r;
        try {
            deadline = System.nanoTime() + timeoutNanos;
            r = write.call(toLua(value), ctx(tag));
        } catch (LuaError | Timeout e) {
            throw new Failure(message(e));
        }
        Object out = toJava(r, null, null);
        if (out == null) throw new Failure("write вернул nil");
        return out;
    }

    private LuaTable ctx(TagEntity tag) {
        return ctxByTag.computeIfAbsent(tag.getName(), name -> {
            LuaTable ctx = new LuaTable();
            ctx.set("tag", str(name));
            ctx.set("device", str(tag.getDeviceName()));
            ctx.set("field", str(tag.getFieldName()));
            ctx.set("device_type", str(tag.getDeviceType()));
            ctx.set("data_type", str(tag.getDataType()));
            ctx.set("unit", str(tag.getUnit()));
            ctx.set("params", params);
            ctx.set("state", new LuaTable());
            return ctx;
        });
    }

    // --------------------------------------------------------------------- песочница --

    private Globals sandbox() {
        Globals g = new Globals();
        g.load(new JseBaseLib());
        g.load(new PackageLib());
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new JseMathLib());
        g.load(new DebugLib());
        LoadState.install(g);
        LuaC.install(g);
        LuaValue sethook = g.get("debug").get("sethook");
        for (String name : REMOVED_GLOBALS) g.set(name, LuaValue.NIL);
        LuaTable string = (LuaTable) g.get("string");
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
        // Перехват по счётчику инструкций на главном потоке стейта — лимит времени вызова.
        sethook.invoke(LuaValue.varargsOf(new LuaValue[]{new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                if (System.nanoTime() > deadline) {
                    throw new Timeout("скрипт выполняется дольше " + timeoutNanos / 1_000_000 + " мс");
                }
                return NIL;
            }
        }, LuaValue.valueOf(""), LuaValue.valueOf(HOOK_EVERY)}));
        return g;
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        // LuaJ печатает конец файла кодом токена: «unexpected symbol 286 (Ğ)».
        return m.replace("unexpected symbol 286 (\u011e)", "неожиданный конец файла (не закрыта конструкция)");
    }

    // -------------------------------------------------------------------- значения --

    private static LuaValue str(String s) {
        return s == null ? LuaValue.NIL : LuaValue.valueOf(s);
    }

    /**
     * Java → Lua. Float отдаётся десятичным значением (64.7f → 64.7, а не 64.69999694824219):
     * скрипт сравнивает с уставками так, как их видит оператор.
     */
    static LuaValue toLua(Object v) {
        if (v == null) return LuaValue.NIL;
        if (v instanceof Boolean b) return LuaValue.valueOf(b);
        if (v instanceof Float f) return LuaValue.valueOf(Double.parseDouble(f.toString()));
        if (v instanceof Number n) return LuaValue.valueOf(n.doubleValue());
        return LuaValue.valueOf(v.toString());
    }

    private static LuaValue toLuaDeep(Object v) {
        if (v instanceof Map<?, ?> m) {
            LuaTable t = new LuaTable();
            m.forEach((k, val) -> t.set(toLua(k), toLuaDeep(val)));
            return t;
        }
        if (v instanceof List<?> l) {
            LuaTable t = new LuaTable();
            for (int i = 0; i < l.size(); i++) t.set(i + 1, toLuaDeep(l.get(i)));
            return t;
        }
        return toLua(v);
    }

    /**
     * Lua → Java с типом по dataType канала: на проводе значение должно остаться типизированным
     * (INT — целым, FLOAT — числом, BOOLEAN — bool). FLOAT канала, снятый как Float, остаётся
     * Float (кратчайшая запись в JSON, как без скрипта).
     *
     * @throws Failure не число/строка/bool, NaN или бесконечность, строка в числовом канале
     */
    static Object toJava(LuaValue v, Object original, String dataType) throws Failure {
        if (v.isnil()) return null;
        if (v.type() == LuaValue.TBOOLEAN) {
            boolean b = v.toboolean();
            if (dataType != null && !ValueCodec.isBool(dataType) && !ValueCodec.isString(dataType)) {
                return ValueCodec.isInt(dataType) ? (Object) (b ? 1 : 0) : (Object) (b ? 1.0 : 0.0);
            }
            return b;
        }
        if (v.type() == LuaValue.TNUMBER) {
            double d = v.todouble();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new Failure("значение не число: " + d);
            if (dataType == null) return d;
            if (ValueCodec.isBool(dataType)) return d != 0;
            if (ValueCodec.isInt(dataType)) {
                long l = Math.round(d);
                return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (Object) (int) l : (Object) l;
            }
            if (ValueCodec.isString(dataType)) return v.tojstring();
            if (original instanceof Float) return (float) d;
            return d;
        }
        if (v.type() == LuaValue.TSTRING) {
            if (dataType != null && !ValueCodec.isString(dataType)) {
                throw new Failure("строка '" + v.tojstring() + "' в канале типа " + dataType);
            }
            return v.tojstring();
        }
        throw new Failure("скрипт вернул " + v.typename() + " вместо значения");
    }
}
