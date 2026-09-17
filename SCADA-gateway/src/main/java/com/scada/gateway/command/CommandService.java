package com.scada.gateway.command;

import com.scada.gateway.pac.PacClientService;
import com.scada.gateway.pac.PacEndpoint;
import com.scada.gateway.model.TagProtocols;
import com.scada.gateway.model.entity.ControllerEntity;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.opcua.ValueCodec;
import com.scada.gateway.service.EventLogService;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Команды записи в ПЛК от Monitor Srv. Пишем только по OPC UA и PAC: Modbus-контроллер
 * (WAGO) отдаёт показания и только на чтение.
 *
 * <p>Выделено из god-класса OpcUaClientServiceDB (шаг 2d декомпозиции). Живыми картами
 * (кэш тегов, OPC UA-клиенты) по-прежнему владеет god-класс — сюда они приходят через
 * УЗКИЕ порты {@link TagCatalog} и {@link OpcUaClientRegistry} (инверсия зависимостей):
 * сервис команд зависит от интерфейсов, а не от god-класса, и в тестах порты
 * подменяются моками. Логика записи перенесена один-в-один.
 */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private final TagCatalog tagCatalog;
    private final OpcUaClientRegistry opcUaClients;
    private final PacClientService pac;
    private final EventLogService eventLog;
    private final long opcuaOpTimeoutMs;

    public CommandService(TagCatalog tagCatalog,
                          OpcUaClientRegistry opcUaClients,
                          PacClientService pac,
                          EventLogService eventLog,
                          @Value("${gateway.opcua-op-timeout-ms:5000}") long opcuaOpTimeoutMs) {
        this.tagCatalog = tagCatalog;
        this.opcUaClients = opcUaClients;
        this.pac = pac;
        this.eventLog = eventLog;
        this.opcuaOpTimeoutMs = opcuaOpTimeoutMs;
    }

    /**
     * Запись значения в тег ПЛК (команда управления от Monitor Srv). Возвращает исход
     * для отправки результата обратно в Monitor.
     */
    public CommandOutcome writeTag(Long tagId, Object value, String dataType) {
        TagEntity tag = tagCatalog.byId(tagId);
        if (tag == null) {
            return new CommandOutcome(false, CommandStatus.REJECTED_UNKNOWN_TAG, "Тег не найден: " + tagId, null);
        }
        // Modbus-контроллер только на чтение — по протоколу, а не по флагу: у holding-
        // регистра нет признака «только чтение», и ошибка в конфиге (writable: true)
        // молча перезаписала бы регистр прибора.
        if (TagProtocols.isModbusTag(tag)) {
            return new CommandOutcome(false, CommandStatus.REJECTED_NOT_WRITABLE,
                    "Modbus-контроллер только на чтение, запись запрещена: " + tag.getName(), null);
        }
        // Доступ к записи — как у реального ПЛК: показание датчика (давление, расход)
        // изменить нельзя, только команду или уставку. Отклоняем ДО похода в контроллер:
        // это экономит round-trip до Bad_NotWritable.
        if (!tag.isWritable()) {
            return new CommandOutcome(false, CommandStatus.REJECTED_NOT_WRITABLE,
                    "Тег только для чтения (датчик), запись запрещена: " + tag.getName(), null);
        }
        // Маршрутизация по протоколу — деталь реализации шлюза, наружу не торчит (A6).
        if (TagProtocols.isOpcUaTag(tag)) {
            return writeOpcUa(tag, value, dataType);
        }
        if (TagProtocols.isPacTag(tag)) {
            return writePac(tag, value, dataType);
        }
        String proto = tag.getProtocol() != null ? tag.getProtocol() : "неизвестный";
        return new CommandOutcome(false, CommandStatus.REJECTED_PROTOCOL_UNSUPPORTED,
                "Запись не реализована для протокола: " + proto, null);
    }

    /**
     * Запись значения по ИМЕНИ канала (полному пути узла). Так тег адресует
     * scada-editor runtime: имя канала — это и Kafka-key телеметрии, и tag_id
     * в редакторе, поэтому внешнему монитору не нужна нумерация тегов шлюза.
     */
    public CommandOutcome writeTagByName(String tagName, Object value, String dataType) {
        TagEntity tag = tagCatalog.byName(tagName);
        if (tag == null) {
            return new CommandOutcome(false, CommandStatus.REJECTED_UNKNOWN_TAG, "Тег не найден по имени: " + tagName, null);
        }
        return writeTag(tag.getId(), value, dataType);
    }

    /** Запись по OPC UA. */
    private CommandOutcome writeOpcUa(TagEntity tag, Object value, String dataType) {
        Long controllerId = tag.getController() != null ? tag.getController().getId() : null;
        OpcUaClient client = opcUaClients.forController(controllerId);
        if (client == null) {
            return new CommandOutcome(false, CommandStatus.FAILED_NO_CONNECTION, "Контроллер не подключён", null);
        }

        // Приведение типа — ОТДЕЛЬНО от записи: ошибка конвертации значения к типу тега
        // это ошибка данных/конфигурации (REJECTED_TYPE_MISMATCH), а не сбой связи.
        NodeId nodeId;
        Variant variant;
        try {
            nodeId = NodeId.parse(tag.getNodeId());
            String dt = dataType != null ? dataType : tag.getDataType();
            variant = ValueCodec.toVariant(dt, value);
        } catch (Exception e) {
            log.warn("Значение '{}' не приводится к типу тега {}: {}", value, tag.getName(), e.getMessage());
            return new CommandOutcome(false, CommandStatus.REJECTED_TYPE_MISMATCH,
                    "Значение не приводится к типу тега: " + e.getMessage(), null);
        }

        try {
            // status/time = null: их проставляет сервер (канон milo для записи).
            DataValue dataValue = new DataValue(variant, null, null);
            // Таймаут: без него зависшая запись вешает поток консьюмера команд навсегда.
            StatusCode status = client.writeValue(nodeId, dataValue).get(opcuaOpTimeoutMs, TimeUnit.MILLISECONDS);

            if (status.isGood()) {
                log.info("✍ OPC UA записано {} = {} (tag {})", tag.getName(), variant.getValue(), tag.getId());
                eventLog.logEvent("COMMAND_APPLIED", "OpcUaClient", "INFO",
                        String.format("Записано %s = %s", tag.getName(), value),
                        Map.of("tagId", tag.getId(), "value", String.valueOf(value)));
                return new CommandOutcome(true, CommandStatus.APPLIED, "Записано значение " + value, value);
            }
            // Разбор неудачного StatusCode на осмысленные для оператора исходы.
            return new CommandOutcome(false, CommandStatusClassifier.classify(status),
                    "OPC UA отклонил запись: " + status, null);

        } catch (Exception e) {
            log.error("Ошибка записи тега {}: {}", tag.getName(), e.getMessage());
            eventLog.logError("OpcUaClient", "Ошибка записи тега " + tag.getName(), e, tag, null);
            return new CommandOutcome(false, CommandStatus.FAILED_WRITE, "Ошибка записи: " + e.getMessage(), null);
        }
    }

    /**
     * Запись команды актуатора по протоколу PAC (driver-master). Шлёт EXEC_DEVICE_COMMAND
     * ({@code __<device>:set_cmd('<field>', 1, value)}) через УЖЕ открытое соединение
     * опроса (как реальный драйвер — команда идёт по тому же каналу). Адрес устройства у
     * PAC — это deviceName/fieldName (а не nodeId/регистр), поэтому они обязательны.
     * boolean кодируется числом 1/0 (в протоколе значения — T_NUMBER).
     */
    private CommandOutcome writePac(TagEntity tag, Object value, String dataType) {
        ControllerEntity ctrl = tag.getController();
        if (ctrl == null || ctrl.getEndpoint() == null) {
            return new CommandOutcome(false, CommandStatus.FAILED_NO_CONNECTION, "Контроллер не задан", null);
        }
        String device = tag.getDeviceName();
        String field = tag.getFieldName();
        if (device == null || field == null) {
            return new CommandOutcome(false, CommandStatus.REJECTED_UNKNOWN_TAG,
                    "У PAC-тега нет device/field для команды: " + tag.getName(), null);
        }
        String host = PacEndpoint.host(ctrl.getEndpoint());
        int port = PacEndpoint.port(ctrl.getEndpoint(), 10000);
        String dt = dataType != null ? dataType : tag.getDataType();

        // Приведение типа — ОТДЕЛЬНО от записи (ошибка данных, а не связи).
        Object typed;
        try {
            if (ValueCodec.isBool(dt)) {
                typed = ValueCodec.toBool(value);
            } else if (ValueCodec.isFloat(dt)) {
                typed = ValueCodec.toFloat(value);
            } else if (ValueCodec.isInt(dt)) {
                typed = ValueCodec.toInt(value);
            } else {
                typed = value;
            }
        } catch (NumberFormatException | ClassCastException e) {
            return new CommandOutcome(false, CommandStatus.REJECTED_TYPE_MISMATCH,
                    "Значение не приводится к типу тега: " + e.getMessage(), null);
        }

        // write использует активное соединение опроса; false — нет связи/ошибка отсылки.
        if (!pac.write(host, port, device, field, typed)) {
            return new CommandOutcome(false, CommandStatus.FAILED_WRITE,
                    "Команда PAC не выполнена (нет активного соединения или ошибка записи)", null);
        }

        log.info("✍ PAC записано {}.{} = {} (tag {})", device, field, typed, tag.getId());
        eventLog.logEvent("COMMAND_APPLIED", "PacClient", "INFO",
                String.format("Записано %s = %s", tag.getName(), value),
                Map.of("tagName", tag.getName(), "value", String.valueOf(value)));
        return new CommandOutcome(true, CommandStatus.APPLIED, "Записано значение " + value, value);
    }
}
