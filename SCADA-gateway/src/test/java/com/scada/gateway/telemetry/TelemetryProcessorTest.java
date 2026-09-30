package com.scada.gateway.telemetry;

import com.scada.gateway.alarm.AlarmEvaluator;
import com.scada.gateway.ha.Leadership;
import com.scada.gateway.kafka.producer.TelemetryProducer;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.model.entity.TelemetryEntity;
import com.scada.gateway.repository.TelemetryRepository;
import com.scada.gateway.script.ValueScripts;
import com.scada.gateway.service.EventLogService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Тесты обработки телеметрии на моках Kafka-продюсера / БД / журнала / алармов.
 * Флаги @Value (alarms.enabled, send-bad-frames) в юнит-тесте = false по умолчанию.
 */
@ExtendWith(MockitoExtension.class)
class TelemetryProcessorTest {

    @Mock TelemetryProducer telemetryProducer;
    @Mock TelemetryRepository telemetryRepository;
    @Mock EventLogService eventLog;
    @Mock AlarmEvaluator alarmEvaluator;

    TelemetryProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TelemetryProcessor(telemetryProducer, telemetryRepository, eventLog, alarmEvaluator,
                new SimpleMeterRegistry(), Leadership.ALWAYS_ACTIVE, ValueScripts.NONE);
    }

    private static TagEntity tag() {
        TagEntity t = new TagEntity();
        t.setId(1L);
        t.setName("temp");
        return t;
    }

    @Test
    @DisplayName("GOOD-значение → уходит в Kafka")
    void good_value_sent_to_kafka() {
        processor.processTagValue(tag(), 42, "GOOD", Instant.now(), null);
        verify(telemetryProducer).sendTelemetry(any(TagEntity.class), eq(42), eq("GOOD"), any(Instant.class));
    }

    @Test
    @DisplayName("Смена качества GOOD→BAD → событие QUALITY_CHANGE в журнал")
    void quality_change_logged() {
        TagEntity t = tag();
        processor.processTagValue(t, 42, "GOOD", Instant.now(), null);
        processor.processTagValue(t, null, "BAD", Instant.now(), null);
        verify(eventLog).logEvent(eq("QUALITY_CHANGE"), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("null-значение при send-bad-frames=false → в Kafka НЕ шлём")
    void null_value_not_sent_when_flag_off() {
        processor.processTagValue(tag(), null, "BAD", Instant.now(), null);
        verifyNoInteractions(telemetryProducer);
    }

    @Test
    @DisplayName("persist-telemetry: точка копится в батч цикла")
    void value_collected_into_batch() {
        List<TelemetryEntity> batch = new ArrayList<>();
        processor.processTagValue(tag(), 42, "GOOD", Instant.now(), batch);
        assertEquals(1, batch.size());
    }

    @Test
    @DisplayName("flushTelemetry → одна батч-вставка saveAll")
    void flush_saves_batch() {
        List<TelemetryEntity> batch = new ArrayList<>();
        batch.add(new TelemetryEntity());
        processor.flushTelemetry(batch);
        verify(telemetryRepository).saveAll(batch);
    }

    @Test
    @DisplayName("Скрипт канала обрабатывает значение ДО Kafka: код обрыва → BAD, смена качества видна")
    void script_runs_before_kafka_and_quality() {
        ValueScripts sensorBreak = new ValueScripts() {
            @Override public Processed process(TagEntity tag, Object v, String q, Instant ts) {
                return v instanceof Number n && Math.abs(n.doubleValue()) > 1000 ? new Processed(null, "BAD") : new Processed(v, q);
            }
            @Override public Object toPlc(TagEntity tag, Object v) { return v; }
        };
        TelemetryProcessor scripted = new TelemetryProcessor(telemetryProducer, telemetryRepository, eventLog,
                alarmEvaluator, new SimpleMeterRegistry(), Leadership.ALWAYS_ACTIVE, sensorBreak);

        scripted.processTagValue(tag(), 21.5, "GOOD", Instant.now(), null);
        scripted.processTagValue(tag(), 3276.7, "GOOD", Instant.now(), null);

        verify(telemetryProducer).sendTelemetry(any(TagEntity.class), eq(21.5), eq("GOOD"), any(Instant.class));
        verify(telemetryProducer, never()).sendTelemetry(any(TagEntity.class), eq(3276.7), anyString(), any(Instant.class));
        verify(eventLog).logEvent(eq("QUALITY_CHANGE"), anyString(), anyString(), anyString(), anyMap());
    }
}
