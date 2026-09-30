package com.scada.gateway.script;

import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.service.ConfigurationService;
import com.scada.gateway.service.EventLogService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Пользовательские Lua-скрипты обработки значений: папка {@code gateway.scripts.dir}
 * (по умолчанию {@code scripts/} в рабочей папке шлюза) с {@code scripts.yaml} и файлами
 * {@code *.lua}.
 *
 * <pre>
 *   scripts:
 *     - script: sensor_break.lua             # файл в этой папке
 *       tags: ["*.LINE?TE?.V"]               # маски имён каналов (см. TagGlob)
 *       params: {valid_min: -50, valid_max: 150}
 *     - script: scale.lua
 *       tags: ["Барановичи-1.BN1_MCA1.M_RPM.LINE1M1.RPM"]
 *       params: {k: 0.1}
 * </pre>
 *
 * <ul>
 *   <li>Несколько привязок на канал — цепочка в порядке файла; команда проходит {@code write}
 *       в обратном порядке.</li>
 *   <li>Изменения файлов подхватываются на ходу ({@code reload-interval-ms}). Новая версия
 *       с ошибкой не применяется — работает прежняя, ошибка — в лог и журнал.</li>
 *   <li>Ошибка при старте (битый YAML, нет файла, синтаксис Lua) — шлюз не стартует: лучше
 *       сразу, чем публиковать значения без нужного пересчёта.</li>
 *   <li>Ошибка скрипта на значении — кадр BAD с value=null (счётчик
 *       {@code scada_script_errors_total}, лог и событие SCRIPT — не чаще раза в минуту на скрипт).</li>
 * </ul>
 */
@Component
public class LuaValueScripts implements ValueScripts {

    private static final Logger log = LoggerFactory.getLogger(LuaValueScripts.class);
    private static final String BINDINGS_FILE = "scripts.yaml";
    private static final long ERROR_LOG_INTERVAL_MS = 60_000;

    /** Загруженный набор скриптов и готовые цепочки по имени канала. */
    private record Loaded(List<BoundScript> scripts, Map<String, List<BoundScript>> byTag, String fingerprint) {
        static final Loaded EMPTY = new Loaded(List.of(), Map.of(), "");
    }

    /** Статистика ошибок скрипта (переживает перезагрузку по имени файла). */
    private static final class Stats {
        final AtomicLong errors = new AtomicLong();
        volatile String lastError;
        volatile Instant lastErrorAt;
        volatile long lastLoggedMs;
    }

    private final Path dir;
    private final Duration timeout;
    private final ConfigurationService configuration;
    private final EventLogService eventLog;
    private final MeterRegistry meterRegistry;
    private final Map<String, Stats> stats = new ConcurrentHashMap<>();
    private volatile Loaded loaded = Loaded.EMPTY;
    private volatile String lastReloadError;

    public LuaValueScripts(@Value("${gateway.scripts.dir:scripts}") String dir,
                           @Value("${gateway.scripts.timeout-ms:50}") long timeoutMs,
                           ConfigurationService configuration,
                           EventLogService eventLog,
                           MeterRegistry meterRegistry) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        this.timeout = Duration.ofMillis(timeoutMs);
        this.configuration = configuration;
        this.eventLog = eventLog;
        this.meterRegistry = meterRegistry;
        Gauge.builder("scada.scripts.bound.tags", this, s -> s.loaded.byTag().size())
                .description("Каналов с пользовательскими скриптами").register(meterRegistry);
    }

    /** Старт: ошибка в скриптах — ошибка старта шлюза. */
    @PostConstruct
    public void init() {
        try {
            loaded = load();
        } catch (BoundScript.Failure | IOException | RuntimeException e) {
            throw new IllegalStateException("Пользовательские скрипты (" + dir + "): " + e.getMessage(), e);
        }
    }

    /** Подхват изменений: папка изменилась — загрузить заново; с ошибкой — оставить прежние. */
    @Scheduled(fixedDelayString = "${gateway.scripts.reload-interval-ms:5000}", initialDelay = 5000)
    public void reloadIfChanged() {
        String fp;
        try {
            fp = fingerprint();
        } catch (IOException e) {
            return;
        }
        if (fp.equals(loaded.fingerprint())) return;
        try {
            loaded = load();
            lastReloadError = null;
            eventLog.logEvent("SCRIPT", "Scripts", "INFO", "Скрипты перезагружены: " + summary(loaded),
                    Map.of("dir", dir.toString()));
        } catch (BoundScript.Failure | IOException | RuntimeException e) {
            // Запоминаем отпечаток сломанной версии, чтобы не повторять ошибку каждые 5 c.
            loaded = new Loaded(loaded.scripts(), loaded.byTag(), fp);
            lastReloadError = e.getMessage();
            log.error("📜 Скрипты не перезагружены, работают прежние: {}", e.getMessage());
            eventLog.logEvent("SCRIPT", "Scripts", "ERROR",
                    "Скрипты не перезагружены, работают прежние: " + e.getMessage(), Map.of("dir", dir.toString()));
        }
    }

    // --------------------------------------------------------------- горячий путь --

    @Override
    public Processed process(TagEntity tag, Object value, String quality, Instant timestamp) {
        List<BoundScript> chain = loaded.byTag().get(tag.getName());
        if (chain == null) return new Processed(value, quality);
        Processed p = new Processed(value, quality);
        for (BoundScript s : chain) {
            try {
                p = s.process(tag, p.value(), p.quality(), timestamp);
            } catch (BoundScript.Failure e) {
                onError(s, tag, e.getMessage());
                return new Processed(null, "BAD");
            }
        }
        return p;
    }

    @Override
    public Object toPlc(TagEntity tag, Object value) throws ScriptFailure {
        List<BoundScript> chain = loaded.byTag().get(tag.getName());
        if (chain == null) return value;
        Object v = value;
        for (int i = chain.size() - 1; i >= 0; i--) {
            BoundScript s = chain.get(i);
            try {
                v = s.toPlc(tag, v);
            } catch (BoundScript.Failure e) {
                onError(s, tag, "write: " + e.getMessage());
                throw new ScriptFailure("скрипт " + s.file + ": " + e.getMessage());
            }
        }
        return v;
    }

    private void onError(BoundScript s, TagEntity tag, String message) {
        Stats st = stats.computeIfAbsent(s.file, f -> new Stats());
        st.errors.incrementAndGet();
        st.lastError = tag.getName() + ": " + message;
        st.lastErrorAt = Instant.now();
        meterRegistry.counter("scada.script.errors", "script", s.file).increment();
        long now = System.currentTimeMillis();
        if (now - st.lastLoggedMs >= ERROR_LOG_INTERVAL_MS) {
            st.lastLoggedMs = now;
            log.error("📜 Скрипт {} на канале {}: {} (ошибок всего: {})", s.file, tag.getName(), message, st.errors.get());
            eventLog.logEvent("SCRIPT", "Scripts", "ERROR", "Скрипт " + s.file + ": " + message,
                    Map.of("script", s.file, "tagName", tag.getName(), "errors", st.errors.get()));
        }
    }

    // --------------------------------------------------------------------- загрузка --

    private Loaded load() throws BoundScript.Failure, IOException {
        Path bindings = dir.resolve(BINDINGS_FILE);
        if (!Files.isRegularFile(bindings)) {
            log.info("📜 Пользовательских скриптов нет ({} не найден)", bindings);
            return new Loaded(List.of(), Map.of(), fingerprint());
        }
        String fp = fingerprint();
        Object root = new Yaml().load(Files.readString(bindings));
        List<?> entries = root instanceof Map<?, ?> m && m.get("scripts") instanceof List<?> l ? l : List.of();

        List<BoundScript> scripts = new ArrayList<>();
        int n = 0;
        for (Object e : entries) {
            n++;
            if (!(e instanceof Map<?, ?> entry)) throw new BoundScript.Failure(BINDINGS_FILE + ": привязка №" + n + " — не объект");
            if (Boolean.FALSE.equals(entry.get("enabled"))) continue;
            String file = entry.get("script") instanceof String f ? f : null;
            if (file == null || file.isBlank()) throw new BoundScript.Failure(BINDINGS_FILE + ": привязка №" + n + " без script");
            Path path = dir.resolve(file).normalize();
            if (!path.startsWith(dir)) throw new BoundScript.Failure(BINDINGS_FILE + ": " + file + " вне папки скриптов");
            if (!Files.isRegularFile(path)) throw new BoundScript.Failure(BINDINGS_FILE + ": нет файла " + file);
            List<TagGlob> globs = new ArrayList<>();
            if (entry.get("tags") instanceof List<?> tags) {
                for (Object t : tags) if (t != null) globs.add(new TagGlob(t.toString()));
            }
            if (globs.isEmpty()) throw new BoundScript.Failure(BINDINGS_FILE + ": у " + file + " не заданы tags");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = entry.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            scripts.add(new BoundScript(file, Files.readString(path), globs, params, timeout));
        }

        Map<String, List<BoundScript>> byTag = new HashMap<>();
        Map<BoundScript, Integer> matched = new LinkedHashMap<>();
        for (BoundScript s : scripts) matched.put(s, 0);
        for (TagEntity tag : configuration.getAllActiveTags()) {
            List<BoundScript> chain = new ArrayList<>();
            for (BoundScript s : scripts) {
                if (s.matches(tag.getName())) {
                    chain.add(s);
                    matched.merge(s, 1, Integer::sum);
                }
            }
            if (!chain.isEmpty()) byTag.put(tag.getName(), List.copyOf(chain));
        }
        matched.forEach((s, count) -> {
            if (count == 0) log.warn("📜 Скрипт {} ни к одному каналу не привязан: маски {}", s.file, s.globs);
        });
        Loaded result = new Loaded(List.copyOf(scripts), Collections.unmodifiableMap(byTag), fp);
        log.info("📜 Скрипты загружены: {}", summary(result));
        return result;
    }

    private static String summary(Loaded l) {
        return l.scripts().size() + " привязок, каналов со скриптами: " + l.byTag().size();
    }

    /** Отпечаток папки: имена, размеры и время изменения файлов. */
    private String fingerprint() throws IOException {
        if (!Files.isDirectory(dir)) return "";
        try (Stream<Path> files = Files.list(dir)) {
            StringBuilder sb = new StringBuilder();
            for (Path p : files.sorted().toList()) {
                if (!Files.isRegularFile(p)) continue;
                sb.append(p.getFileName()).append(':').append(Files.size(p)).append(':')
                        .append(Files.getLastModifiedTime(p).toMillis()).append(';');
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------- для REST --

    /** Состояние скриптов для GET /api/scripts. */
    public Map<String, Object> info() {
        Loaded l = loaded;
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (BoundScript s : l.scripts()) {
            long tags = l.byTag().values().stream().filter(chain -> chain.contains(s)).count();
            Stats st = stats.get(s.file);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("script", s.file);
            b.put("tags", s.globs.stream().map(TagGlob::toString).toList());
            b.put("matchedTags", tags);
            b.put("write", s.hasWrite());
            b.put("errors", st != null ? st.errors.get() : 0);
            b.put("lastError", st != null ? st.lastError : null);
            b.put("lastErrorAt", st != null && st.lastErrorAt != null ? st.lastErrorAt.toString() : null);
            bindings.add(b);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", dir.toString());
        out.put("bindings", bindings);
        out.put("taggedChannels", l.byTag().size());
        out.put("lastReloadError", lastReloadError);
        return out;
    }
}
