package com.scada.gateway.pac;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;

import static org.junit.jupiter.api.Assertions.*;

/** Извлечение значений из Lua-снимка PAC (как это делает драйвер через свой Lua). */
class PacLuaTest {

    /** Фрагмент ответа GET_DEVICES_STATES эмулятора ptusa 2026.4.2.1 (проект BN1-МСА1), значения подправлены. */
    private static final String SNAPSHOT = """
            t=
            \t{
            \tLINE1V0={M=0, ST=1},
            \tLINE1M1={M=0, ST=0, V=0, R=0, FRQ=12.5, RPM=750, EST=0, AMP=0.0, MAX_FRQ=0.0, P_ON_TIME=1000},
            \t}
            t.OBJECT1 = t.OBJECT1 or {}
            t.OBJECT1=
            \t{
            \tCMD=0,
            \tCUR_REC='Танк сырого молока №1',
            \tRT_PAR_F=
            \t\t{
            \t\t0, 0, 0, 0, 0, 0, 0.98, 0, 0, 0, 0, 1,\s
            \t\t},
            \tPAR_MAIN=
            \t\t{
            \t\t1, 0.20, 0.15,\s
            \t\t},
            \t}
            t.SYSTEM =
            \t{
            \tP_V_OFF_DELAY_TIME=1000,
            \t}
            """;

    private static Globals snapshot() {
        Globals g = PacLua.newState();
        PacLua.exec(g, SNAPSHOT);
        return g;
    }

    @Test
    void readsDeviceFieldsByDataType() {
        Globals g = snapshot();
        assertEquals(1L, PacLua.read(g, "LINE1V0", "ST", "INT32"));
        assertEquals(750L, PacLua.read(g, "LINE1M1", "RPM", "INT32"));
        assertEquals(12.5, (Double) PacLua.read(g, "LINE1M1", "FRQ", "FLOAT"), 1e-9);
        assertEquals(Boolean.TRUE, PacLua.read(g, "LINE1V0", "ST", "BOOLEAN"));
        assertEquals(1000L, PacLua.read(g, "SYSTEM", "P_V_OFF_DELAY_TIME", "INT32"));
    }

    @Test
    void readsArrayElementsByLuaIndex() {
        Globals g = snapshot();
        assertEquals(0.98, (Double) PacLua.read(g, "OBJECT1", "RT_PAR_F[7]", "FLOAT"), 1e-9);
        assertEquals(1.0, (Double) PacLua.read(g, "OBJECT1", "RT_PAR_F[12]", "FLOAT"), 1e-9);
        // Хвост после ] — подпись канала в базе, а не часть адреса в ПЛК.
        assertEquals(0.20, (Double) PacLua.read(g, "OBJECT1", "PAR_MAIN[2].P_CMIN_S", "FLOAT"), 1e-9);
    }

    @Test
    void readsStrings() {
        assertEquals("Танк сырого молока №1", PacLua.read(snapshot(), "OBJECT1", "CUR_REC", "STRING"));
    }

    @Test
    void intTruncatesToLong() {
        assertEquals(12L, PacLua.read(snapshot(), "LINE1M1", "FRQ", "INT"));
    }

    @Test
    void missingOrUnreadable_null() {
        Globals g = PacLua.newState();
        assertNull(PacLua.read(g, "LINE1V0", "ST", "INT32"), "нет снимка t → null");

        g = snapshot();
        assertNull(PacLua.read(g, "LINE9V9", "ST", "INT32"), "нет прибора → null");
        assertNull(PacLua.read(g, "LINE1V0", "V", "FLOAT"), "нет поля → null");
        assertNull(PacLua.read(g, "OBJECT1", "RT_PAR_F[99]", "FLOAT"), "нет элемента массива → null");
        assertNull(PacLua.read(g, "OBJECT1", "RT_PAR_F[x]", "FLOAT"), "кривой индекс → null");
        assertNull(PacLua.read(g, "OBJECT1", "CUR_REC", "FLOAT"), "строка в числовом теге → null");
        assertNull(PacLua.read(g, null, "ST", "INT32"), "тег без deviceName → null");
    }

    @Test
    void stateHasNoHostAccess() {
        Globals g = PacLua.newState();
        for (String lib : new String[]{"os", "io", "luajava", "require", "package", "dofile", "loadfile",
                "debug", "load", "loadstring", "pcall", "xpcall", "collectgarbage", "getfenv", "setfenv"}) {
            assertTrue(g.get(lib).isnil(), lib + " не должен быть доступен скрипту контроллера");
        }
        assertTrue(g.get("string").get("dump").isnil(), "string.dump — сборка байткода");
        // Вычисления, которые нужны снимку, остаются.
        PacLua.exec(g, "t = t or {}\nt.X = {V = math.max(1, 2), S = string.upper('ok')}\n");
        assertEquals(2.0, (Double) PacLua.read(g, "X", "V", "FLOAT"), 1e-9);
    }

    @Test
    void bytecodeCannotBeLoaded() {
        Globals g = PacLua.newState();
        // Байткод Lua не проверяется: собранный скриптом (string.dump → loadstring) — выход из песочницы.
        assertThrows(LuaError.class, () -> PacLua.exec(g, "f = loadstring(string.dump(function() return 42 end))"));
        assertThrows(LuaError.class, () -> PacLua.exec(g, "s = string.dump(print)"));
    }

    @Test
    // Регрессия (нет лимита времени) — зависший тест; SEPARATE_THREAD превращает её в падение.
    @org.junit.jupiter.api.Timeout(value = 15, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void endlessScriptIsStoppedByTimeBudget() {
        Globals g = PacLua.newState();
        long t0 = System.nanoTime();
        LuaError e = assertThrows(LuaError.class, () -> PacLua.exec(g, "while true do end", 200));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 2000, "цикл оборван за " + ms + " мс");
        assertTrue(e.getMessage().contains("дольше"), e.getMessage());
        // Лимит — на каждый скрипт, а не на жизнь стейта: следующий ответ разбирается.
        PacLua.exec(g, SNAPSHOT);
        assertEquals(1L, PacLua.read(g, "LINE1V0", "ST", "INT32"));
    }

    @Test
    // Регрессия (нет лимита времени) — зависший тест; SEPARATE_THREAD превращает её в падение.
    @org.junit.jupiter.api.Timeout(value = 15, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void timeoutCannotBeSwallowedByScript() {
        Globals g = PacLua.newState();
        // pcall убран; и Timeout — Error, а не ошибка Lua: даже через coroutine его не перехватить.
        assertThrows(LuaError.class, () -> PacLua.exec(g,
                "local co = coroutine.create(function() while true do end end)\n"
                        + "coroutine.resume(co)\nwhile true do end", 200));
    }

    @Test
    void stringBombIsRejected() {
        Globals g = PacLua.newState();
        LuaError e = assertThrows(LuaError.class, () -> PacLua.exec(g, "s = string.rep('x', 64 * 1024 * 1024)"));
        assertTrue(e.getMessage().contains("string.rep"), e.getMessage());
    }

    @Test
    void protocolVersion_fromInfo() {
        Globals g = PacLua.newState();
        PacLua.exec(g, "protocol_version = 104; PAC_name = \"BN1-МСА1\"; is_reset_params = 0;params_CRC=32634;\n");
        assertEquals(104, PacLua.protocolVersion(g));
    }

    @Test
    void scalar_boolAsNumber() {
        assertEquals("1", PacLua.scalar(Boolean.TRUE));
        assertEquals("0", PacLua.scalar(Boolean.FALSE));
        assertEquals("42.5", PacLua.scalar(42.5));
    }
}
