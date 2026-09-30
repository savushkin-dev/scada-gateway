package com.scada.gateway.script;

import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.service.ConfigurationService;
import com.scada.gateway.service.EventLogService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Пользовательские Lua-скрипты: примеры из SCADA-gateway/scripts/ на настоящих именах каналов,
 * типы значений на проводе, цепочки и обратная запись, песочница и лимиты, подхват изменений.
 */
class LuaValueScriptsTest {

    private static final Path EXAMPLES = Path.of("scripts");
    private static final String TE = "Барановичи-1.BN1_MCA1.TE_V.LINE2TE1.V";
    private static final String ON_TIME = "Барановичи-1.BN1_MCA1.M_P_ON_TIME.LINE1M1.P_ON_TIME";
    private static final String QT = "Барановичи-1.BN1_MCA1.QT_V.LINE2QT1.V";
    private static final String COUNTER = "Барановичи-1.BN1_MCA1.Счётчик.OBJECT1.CNT";
    private static final String STATE = "Барановичи-1.BN1_MCA1.Состояние.OBJECT1.STATE";

    @TempDir
    Path dir;

    private static TagEntity tag(String name, String dataType) {
        TagEntity t = new TagEntity();
        t.setId((long) name.hashCode());
        t.setName(name);
        t.setDataType(dataType);
        return t;
    }

    private static final List<TagEntity> TAGS = List.of(
            tag(TE, "FLOAT"), tag(ON_TIME, "FLOAT"), tag(QT, "FLOAT"), tag(COUNTER, "INT32"), tag(STATE, "STRING"),
            tag("A.OBJECT1.RT_PAR_F[7]", "FLOAT"), tag("A.OBJECT1.RT_PAR_F[17]", "FLOAT"), tag("A.OBJECT1.RT_PAR_F7", "FLOAT"));

    private static TagEntity byName(String name) {
        return TAGS.stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    private LuaValueScripts scripts(String bindingsYaml) throws IOException {
        Files.writeString(dir.resolve("scripts.yaml"), bindingsYaml);
        ConfigurationService config = mock(ConfigurationService.class);
        when(config.getAllActiveTags()).thenReturn(TAGS);
        LuaValueScripts s = new LuaValueScripts(dir.toString(), 50, config, mock(EventLogService.class),
                new SimpleMeterRegistry());
        s.init();
        return s;
    }

    private void script(String file, String source) throws IOException {
        Files.writeString(dir.resolve(file), source);
    }

    private void copyExamples() throws IOException {
        for (String f : List.of("sensor_break.lua", "scale.lua", "deadband.lua")) {
            Files.copy(EXAMPLES.resolve(f), dir.resolve(f));
        }
    }

    private static ValueScripts.Processed run(ValueScripts s, String tag, Object value) {
        return s.process(byName(tag), value, value == null ? "BAD" : "GOOD", Instant.now());
    }

    // ----------------------------------------------------------------- примеры --

    @Test
    @DisplayName("sensor_break.lua: код обрыва ±3276.7 → BAD без значения, норма проходит")
    void sensorBreakExample() throws IOException {
        copyExamples();
        ValueScripts s = scripts("""
                scripts:
                  - script: sensor_break.lua
                    tags: ["Барановичи-1.BN1_MCA1.TE_V.*.V"]
                    params: {valid_min: -50, valid_max: 150}
                """);
        assertThat(run(s, TE, 3276.7f)).isEqualTo(new ValueScripts.Processed(null, "BAD"));
        assertThat(run(s, TE, -3276.7f)).isEqualTo(new ValueScripts.Processed(null, "BAD"));
        assertThat(run(s, TE, 64.7f)).isEqualTo(new ValueScripts.Processed(64.7f, "GOOD"));
        // Канал без привязки — как есть.
        assertThat(run(s, QT, 3276.7f)).isEqualTo(new ValueScripts.Processed(3276.7f, "GOOD"));
    }

    @Test
    @DisplayName("scale.lua: мс → с на чтении, команда оператора в секундах → мс в ПЛК")
    void scaleExampleBothWays() throws Exception {
        copyExamples();
        ValueScripts s = scripts("""
                scripts:
                  - script: scale.lua
                    tags: ["Барановичи-1.BN1_MCA1.M_P_ON_TIME.LINE?M*.P_ON_TIME"]
                    params: {k: 0.001}
                """);
        assertThat(run(s, ON_TIME, 1500.0).value()).isEqualTo(1.5);
        assertThat(s.toPlc(byName(ON_TIME), 2.5)).isEqualTo(2500.0);
        assertThat(s.toPlc(byName(QT), 2.5)).as("без скрипта — как есть").isEqualTo(2.5);
    }

    @Test
    @DisplayName("deadband.lua: дрожание меньше delta держит значение, ctx.state у канала свой")
    void deadbandExampleKeepsStatePerChannel() throws IOException {
        copyExamples();
        ValueScripts s = scripts("""
                scripts:
                  - script: deadband.lua
                    tags: ["*.QT_V.*.V", "*.TE_V.*.V"]
                    params: {delta: 0.05}
                """);
        assertThat(run(s, QT, 10.0).value()).isEqualTo(10.0);
        assertThat(run(s, QT, 10.03).value()).isEqualTo(10.0);
        assertThat(run(s, TE, 50.0).value()).as("у другого канала своё состояние").isEqualTo(50.0);
        assertThat(run(s, QT, 10.2).value()).isEqualTo(10.2);
        assertThat(run(s, QT, null)).isEqualTo(new ValueScripts.Processed(null, "BAD"));
        assertThat(run(s, QT, 10.21).value()).as("после обрыва — сразу").isEqualTo(10.21);
    }

    // ------------------------------------------------------------------ типы --

    @Test
    @DisplayName("Тип на проводе — по dataType: Float остаётся Float (64.7, не 64.69999…), INT — целым")
    void wireTypesFollowDataType() throws IOException {
        script("same.lua", "function process(v, q, ctx) return v, q end");
        script("half.lua", "function process(v, q, ctx) return v / 2 + 0.6 end");
        script("text.lua", "function process(v, q, ctx) return 'Мойка ' .. v end");
        ValueScripts s = scripts("""
                scripts:
                  - {script: same.lua, tags: ["*.TE_V.*"]}
                  - {script: half.lua, tags: ["*.CNT"]}
                  - {script: text.lua, tags: ["*.STATE"]}
                """);
        Object v = run(s, TE, 64.7f).value();
        assertThat(v).isInstanceOf(Float.class).isEqualTo(64.7f);
        assertThat(run(s, COUNTER, 5).value()).isEqualTo(3); // 5/2+0.6 = 3.1 → 3
        assertThat(run(s, STATE, "ожидание").value()).isEqualTo("Мойка ожидание");
    }

    @Test
    @DisplayName("Строка в числовом канале, NaN, неверное качество — ошибка скрипта → BAD")
    void badResultsBecomeBad() throws IOException {
        script("str.lua", "function process(v, q, ctx) return 'abc' end");
        script("nan.lua", "function process(v, q, ctx) return 0/0 end");
        script("qual.lua", "function process(v, q, ctx) return v, 'OK' end");
        ValueScripts s = scripts("""
                scripts:
                  - {script: str.lua, tags: ["*.TE_V.*"]}
                  - {script: nan.lua, tags: ["*.QT_V.*"]}
                  - {script: qual.lua, tags: ["*.CNT"]}
                """);
        ValueScripts.Processed bad = new ValueScripts.Processed(null, "BAD");
        assertThat(run(s, TE, 1.0f)).isEqualTo(bad);
        assertThat(run(s, QT, 1.0)).isEqualTo(bad);
        assertThat(run(s, COUNTER, 1)).isEqualTo(bad);
    }

    // --------------------------------------------------------- маски и цепочки --

    @Test
    @DisplayName("Маска: [ ] — буквально, ? — один символ")
    void globBracketsAreLiteral() {
        TagGlob g = new TagGlob("*.OBJECT1.RT_PAR_F[7]");
        assertThat(g.matches("A.OBJECT1.RT_PAR_F[7]")).isTrue();
        assertThat(g.matches("A.OBJECT1.RT_PAR_F[17]")).isFalse();
        assertThat(g.matches("A.OBJECT1.RT_PAR_F7")).isFalse();
        assertThat(new TagGlob("*.LINE?M*.P_ON_TIME").matches(ON_TIME)).isTrue();
        assertThat(new TagGlob("*.LINE?M*.P_ON_TIME").matches("X.LINE12M1.P_ON_TIME")).isFalse();
    }

    @Test
    @DisplayName("Цепочка — в порядке scripts.yaml; команда — через write() в обратном порядке")
    void chainOrderAndReverseWrite() throws Exception {
        copyExamples();
        ValueScripts s = scripts("""
                scripts:
                  - {script: scale.lua, tags: ["*.QT_V.*"], params: {k: 2}}
                  - {script: scale.lua, tags: ["*.QT_V.*"], params: {b: 1}}
                """);
        assertThat(run(s, QT, 10.0).value()).isEqualTo(21.0);            // 10*2 + 1
        assertThat(s.toPlc(byName(QT), 21.0)).isEqualTo(10.0);           // (21-1)/2
    }

    // ------------------------------------------------------------ песочница --

    @Test
    @DisplayName("Бесконечный цикл обрывается лимитом времени: BAD, ошибка видна в /api/scripts")
    void endlessLoopIsStopped() throws IOException {
        script("loop.lua", "function process(v, q, ctx) while true do end end");
        LuaValueScripts s = scripts("scripts:\n  - {script: loop.lua, tags: [\"*.QT_V.*\"]}\n");
        long t0 = System.nanoTime();
        assertThat(run(s, QT, 1.0)).isEqualTo(new ValueScripts.Processed(null, "BAD"));
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1000);
        @SuppressWarnings("unchecked")
        Map<String, Object> binding = ((List<Map<String, Object>>) s.info().get("bindings")).get(0);
        assertThat(binding.get("errors")).isEqualTo(1L);
        assertThat((String) binding.get("lastError")).contains("дольше 50 мс");
        // Стейт жив: следующий вызов снова обрывается, а не висит.
        assertThat(run(s, QT, 2.0)).isEqualTo(new ValueScripts.Processed(null, "BAD"));
    }

    @Test
    @DisplayName("Песочница: нет os/io/load/pcall/debug; string.rep ограничен")
    void sandboxHasNoHostAccess() throws IOException {
        script("probe.lua", """
                function process(v, q, ctx)
                  return tostring(os) .. tostring(io) .. tostring(load) .. tostring(loadstring)
                      .. tostring(pcall) .. tostring(debug) .. tostring(require) .. tostring(string.dump)
                end
                """);
        script("bomb.lua", "function process(v, q, ctx) return string.rep('x', 1e9) end");
        ValueScripts s = scripts("""
                scripts:
                  - {script: probe.lua, tags: ["*.STATE"]}
                  - {script: bomb.lua, tags: ["*.TE_V.*"]}
                """);
        assertThat(run(s, STATE, "x").value()).isEqualTo("nil".repeat(8));
        assertThat(run(s, TE, 1.0f)).isEqualTo(new ValueScripts.Processed(null, "BAD"));
    }

    // ------------------------------------------------------ загрузка и подхват --

    @Test
    @DisplayName("Нет папки или scripts.yaml — значения как есть")
    void noScriptsIsPassThrough() {
        LuaValueScripts s = new LuaValueScripts(dir.resolve("нет").toString(), 50, mock(ConfigurationService.class),
                mock(EventLogService.class), new SimpleMeterRegistry());
        s.init();
        assertThat(run(s, TE, 3276.7f)).isEqualTo(new ValueScripts.Processed(3276.7f, "GOOD"));
    }

    @Test
    @DisplayName("Ошибка при старте (нет файла, синтаксис Lua, нет process) — шлюз не стартует")
    void startupErrorsFailFast() throws IOException {
        assertThatThrownBy(() -> scripts("scripts:\n  - {script: nope.lua, tags: [\"*\"]}\n"))
                .hasMessageContaining("нет файла nope.lua");
        script("syntax.lua", "function process(v, q, ctx) return v +");
        assertThatThrownBy(() -> scripts("scripts:\n  - {script: syntax.lua, tags: [\"*\"]}\n"))
                .hasMessageContaining("syntax.lua");
        script("noproc.lua", "x = 1");
        assertThatThrownBy(() -> scripts("scripts:\n  - {script: noproc.lua, tags: [\"*\"]}\n"))
                .hasMessageContaining("нет функции process");
        assertThatThrownBy(() -> scripts("scripts:\n  - {script: ../x.lua, tags: [\"*\"]}\n"))
                .hasMessageContaining("вне папки");
    }

    @Test
    @DisplayName("Изменение скрипта подхватывается на ходу; сломанная версия не применяется")
    void hotReloadKeepsOldOnError() throws IOException {
        script("k.lua", "function process(v, q, ctx) return v * 2 end");
        LuaValueScripts s = scripts("scripts:\n  - {script: k.lua, tags: [\"*.QT_V.*\"]}\n");
        assertThat(run(s, QT, 10.0).value()).isEqualTo(20.0);

        rewrite("k.lua", "function process(v, q, ctx) return v * 3 end");
        s.reloadIfChanged();
        assertThat(run(s, QT, 10.0).value()).isEqualTo(30.0);

        rewrite("k.lua", "function process(v, q, ctx) return v *");
        s.reloadIfChanged();
        assertThat(run(s, QT, 10.0).value()).as("работает прежняя версия").isEqualTo(30.0);
        assertThat((String) s.info().get("lastReloadError")).contains("k.lua").contains("неожиданный конец файла");
    }

    /** Переписать файл со сдвигом времени изменения (иначе в пределах одной мс его не видно). */
    private void rewrite(String file, String source) throws IOException {
        Path p = dir.resolve(file);
        FileTime before = Files.getLastModifiedTime(p);
        Files.writeString(p, source);
        Files.setLastModifiedTime(p, FileTime.fromMillis(before.toMillis() + 2000));
    }
}
