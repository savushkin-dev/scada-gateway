package com.scada.gateway.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scada.gateway.alarm.AlarmEvaluator;
import com.scada.gateway.ha.Leadership;
import com.scada.gateway.kafka.producer.TelemetryProducer;
import com.scada.gateway.model.entity.ControllerEntity;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.model.entity.TelemetryEntity;
import com.scada.gateway.repository.TelemetryRepository;
import com.scada.gateway.script.ValueScripts;
import com.scada.gateway.service.EventLogService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обработка снятого значения тега: смена качества → событие, гейт алармов, буфер
 * телеметрии для батч-записи в БД и отправка значения в Kafka.
 *
 * <p>Выделено из god-класса OpcUaClientServiceDB (горячий путь опроса). Батчем точек
 * цикла (persist-telemetry) владеет вызывающий поток опроса — сюда он приходит
 * параметром {@code batch}, а flush вызывает он же в конце цикла. Логика перенесена
 * один-в-один; alarms.enabled/send-bad-frames — свои @Value этого бина.
 */
@Component
public class TelemetryProcessor {

    private static final Logger log = LoggerFactory.getLogger(TelemetryProcessor.class);

    private final TelemetryProducer telemetryProducer;
    private final TelemetryRepository telemetryRepository;
    private final EventLogService eventLog;
    private final AlarmEvaluator alarmEvaluator;
    private final Counter telemetrySent;
    /** Горячий резерв: историю в БД пишет только активный (иначе пара писала бы каждую точку дважды). */
    private final Leadership leadership;
    /** Пользовательские Lua-скрипты обработки значений (scripts/): до качества, алармов и Kafka. */
    private final ValueScripts scripts;

    /** Считать ли пороги/алармы в шлюзе. По умолчанию false — алармы считает Monitor. */
    @Value("${gateway.alarms.enabled:false}")
    private boolean alarmsEnabled;
    /**
     * A3: слать ли телеметрию при value==null (обрыв, quality=BAD). По умолчанию
     * false — иначе null как falsy «уверенно закрыл бы клапан» на фронте без правок
     * B2/C4. Включать только когда монитор (C4) и фронт (B2) готовы к «нет данных».
     */
    @Value("${gateway.send-bad-frames:false}")
    private boolean sendBadFrames;

    // Последнее качество по тегу — чтобы порождать событие при смене GOOD↔BAD.
    private final Map<Long, String> lastQualityByTag = new ConcurrentHashMap<>();

    private final ObjectMapper recordMapper = new ObjectMapper();

    public TelemetryProcessor(TelemetryProducer telemetryProducer,
                              TelemetryRepository telemetryRepository,
                              EventLogService eventLog,
                              AlarmEvaluator alarmEvaluator,
                              MeterRegistry meterRegistry,
                              Leadership leadership,
                              ValueScripts scripts) {
        this.telemetryProducer = telemetryProducer;
        this.telemetryRepository = telemetryRepository;
        this.eventLog = eventLog;
        this.alarmEvaluator = alarmEvaluator;
        this.leadership = leadership;
        this.scripts = scripts;
        this.telemetrySent = meterRegistry.counter("scada.telemetry.sent.total");
    }

    /**
     * Разбор СЫРОЙ ЗАПИСИ ПРИБОРА (роль реального драйвера PAC_easy_drv_LZ).
     * Значение record — строка вида "ИМЯ={ПОЛЕ=знач, ПОЛЕ=знач, ...}". Метод:
     *   1) вырезает содержимое {...} и разбивает на пары поле=значение;
     *   2) по карте fieldsJson находит для поля channelId (node.id базы) и тип;
     *   3) приводит значение к типу и шлёт КАЖДОЕ поле в Kafka отдельным каналом
     *      (tagId = node.id), device/field/type — в metadata.
     */
    public void processDeviceRecord(TagEntity tag, Object record, String quality, Instant timestamp, ControllerEntity controller) {
        if (record == null || tag.getFieldsJson() == null) return;
        String s = record.toString();
        int lb = s.indexOf('{'), rb = s.lastIndexOf('}');
        if (lb < 0 || rb <= lb) {
            log.warn("Запись прибора {} без {{}}: {}", tag.getDeviceName(), s);
            return;
        }
        // Разбираем пары поле=значение из содержимого { ... }.
        Map<String, String> parsed = new HashMap<>();
        for (String part : s.substring(lb + 1, rb).split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) parsed.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        // Карта полей: [{"name":"ST","channelId":385,"dataType":"BOOLEAN"},…].
        List<Map<String, Object>> fields;
        try {
            fields = recordMapper.readValue(tag.getFieldsJson(), List.class);
        } catch (Exception e) {
            log.error("fieldsJson прибора {} не разобран: {}", tag.getDeviceName(), e.getMessage());
            return;
        }
        for (Map<String, Object> f : fields) {
            String field = (String) f.get("name");
            String raw = parsed.get(field);
            if (raw == null) continue;
            String dt = String.valueOf(f.get("dataType"));
            Object value;
            try {
                value = "BOOLEAN".equalsIgnoreCase(dt)
                        ? (!"0".equals(raw) && !raw.isEmpty())
                        : Double.parseDouble(raw);
            } catch (NumberFormatException nfe) {
                continue;
            }
            String channelName = tag.getDeviceName() + "." + field;
            telemetryProducer.sendFieldTelemetry(channelName, value, quality, timestamp);
        }
    }

    /**
     * Горячий путь обработки одного снятого значения тега: событие при смене качества,
     * (опционально) оценка алармов, буферизация в batch для батч-записи в БД и отправка
     * значения в Kafka. batch != null ⇔ persist-telemetry=true; flush делает вызывающий.
     */
    public void processTagValue(TagEntity tag, Object value, String quality, Instant timestamp, List<TelemetryEntity> batch) {
        // Пользовательский скрипт канала (масштаб, отсев кода обрыва, фильтр) — первым: всё
        // дальше (качество, алармы, история, Kafka) видит уже обработанное значение.
        ValueScripts.Processed processed = scripts.process(tag, value, quality, timestamp);
        value = processed.value();
        quality = processed.quality();

        // Чтение тега НЕ логируем в event_log на каждый опрос. Значения идут в Kafka
        // (и, если включён persist-telemetry, в локальную БД батчем в конце цикла);
        // в журнал событий пишем только смену качества и ошибки.

        // Событие при смене качества сигнала (GOOD↔BAD) — попадает в журнал событий.
        String prevQuality = lastQualityByTag.put(tag.getId(), quality);
        if (prevQuality != null && !prevQuality.equals(quality)) {
            String sev = "GOOD".equalsIgnoreCase(quality) ? "INFO" : "WARNING";
            Map<String, Object> details = new HashMap<>();
            details.put("tagId", tag.getId());
            details.put("tagName", tag.getName());
            details.put("from", prevQuality);
            details.put("to", quality);
            eventLog.logEvent("QUALITY_CHANGE", "OpcUaClient", sev,
                    String.format("Quality changed for %s: %s → %s", tag.getName(), prevQuality, quality),
                    details);
        }

        // Пороги/алармы — только если включены флагом (по умолчанию их считает Monitor).
        if (alarmsEnabled && value instanceof Number) {
            alarmEvaluator.evaluate(tag, ((Number) value).doubleValue());
        }

        // Локальная история: копим в буфер цикла (batch != null ⇔ persist-telemetry=true).
        if (batch != null) {
            batch.add(buildTelemetry(tag, value, quality, timestamp));
        }

        if (value != null) {
            telemetryProducer.sendTelemetry(tag, value, quality, timestamp);
            // Резервный экземпляр пары не публикует — и не считает (метрика = реально отправленное).
            if (leadership.isActive()) telemetrySent.increment();
            // per-tag на каждый опрос — только debug (иначе поток INFO на 2471 тег/цикл).
            log.debug("📊 {} = {}", tag.getName(), value);
        } else {
            // A3: NULL при обрыве. BAD-кадр шлём только при включённом флаге — иначе тег
            // замирает на последнем значении (безопаснее, чем falsy null на фронте без
            // правок B2/C4). Состояние связи фиксирует markControllerDown на уровне цикла.
            if (sendBadFrames) {
                telemetryProducer.sendTelemetry(tag, null, quality, timestamp);
                if (leadership.isActive()) telemetrySent.increment();
            }
            log.debug("⚠️ {} = NULL (quality: {})", tag.getName(), quality);
        }
    }

    /**
     * Строит строку telemetry для БД: число/boolean → числовая колонка value
     * (boolean как 1.0/0.0), прочее → строковая колонка value_str.
     */
    private TelemetryEntity buildTelemetry(TagEntity tag, Object value, String quality, Instant timestamp) {
        TelemetryEntity t = new TelemetryEntity();
        t.setTagId(tag.getId());
        t.setTime(timestamp);
        t.setQuality(quality);
        if (value instanceof Number) {
            t.setValue(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            t.setValue(((Boolean) value) ? 1.0 : 0.0);
        } else if (value != null) {
            t.setValueString(value.toString());
        }
        return t;
    }

    /** Батч-запись точек цикла: saveAll = ОДНА транзакция вместо N отдельных коммитов. */
    public void flushTelemetry(List<TelemetryEntity> batch) {
        if (!leadership.isActive()) return;
        try {
            telemetryRepository.saveAll(batch);
        } catch (Exception e) {
            log.error("DB batch save error ({} точек): {}", batch.size(), e.getMessage());
            eventLog.logError("Database", "Failed to batch-save telemetry", e, null, null);
        }
    }
}
