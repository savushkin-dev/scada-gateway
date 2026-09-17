package com.scada.gateway.command;

import com.scada.gateway.pac.PacClientService;
import com.scada.gateway.model.entity.ControllerEntity;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.service.EventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Тесты сервиса команд на МОКАХ портов (TagCatalog, OpcUaClientRegistry) и PAC.
 *
 * <p>Смысл декомпозиции 2d виден именно здесь: живые карты god-класса подменены
 * заглушками (Mockito), поэтому логику маршрутизации и исходов команды можно
 * проверить без реального ПЛК, БД и Kafka. @Mock создаёт заглушку, when(...).thenReturn
 * задаёт её ответ, verify(...) проверяет, что зависимость вызвана (или НЕ вызвана).
 *
 * <p>Путь «OPC UA запись УСПЕШНА» здесь НЕ мокаем: это потребовало бы подделывать
 * async-внутренности Milo OpcUaClient — хрупкий тест низкой ценности. Он честнее
 * проверяется E2E на стенде (реальная команда монитор→шлюз→Phoenix).
 */
@ExtendWith(MockitoExtension.class)
class CommandServiceTest {

    @Mock TagCatalog tagCatalog;
    @Mock OpcUaClientRegistry opcUaClients;
    @Mock PacClientService pac;
    @Mock EventLogService eventLog;

    CommandService service;

    @BeforeEach
    void setUp() {
        service = new CommandService(tagCatalog, opcUaClients, pac, eventLog, 5000L);
    }

    private static TagEntity writableModbusTag() {
        TagEntity t = new TagEntity();
        t.setName("WAGO.reg");
        t.setProtocol("modbus");
        t.setModbusAddress(40001);
        t.setModbusUnitId(1);
        t.setDataType("INT");
        t.setWritable(true);
        ControllerEntity c = new ControllerEntity();
        c.setEndpoint("modbus://wago:5020");
        t.setController(c);
        return t;
    }

    private static TagEntity writableOpcUaTag(long controllerId) {
        TagEntity t = new TagEntity();
        t.setName("phoenix.tag");
        t.setProtocol("opcua");
        t.setNodeId("ns=2;s=Tag");
        t.setDataType("BOOL");
        t.setWritable(true);
        ControllerEntity c = new ControllerEntity();
        c.setId(controllerId);
        t.setController(c);
        return t;
    }

    @Test
    @DisplayName("Неизвестный тег по id → REJECTED_UNKNOWN_TAG")
    void unknown_tag_by_id() {
        when(tagCatalog.byId(1L)).thenReturn(null);
        CommandOutcome out = service.writeTag(1L, 5, "INT");
        assertFalse(out.success);
        assertEquals(CommandStatus.REJECTED_UNKNOWN_TAG, out.status);
    }

    @Test
    @DisplayName("Датчик (writable=false) → REJECTED_NOT_WRITABLE, БЕЗ похода в контроллер")
    void not_writable_rejected_before_io() {
        TagEntity t = writableOpcUaTag(7L);
        t.setWritable(false);
        when(tagCatalog.byId(1L)).thenReturn(t);
        CommandOutcome out = service.writeTag(1L, true, "BOOL");
        assertEquals(CommandStatus.REJECTED_NOT_WRITABLE, out.status);
        verifyNoInteractions(opcUaClients);   // защита сработала ДО записи
    }

    @Test
    @DisplayName("OPC UA-тег, контроллер не подключён → FAILED_NO_CONNECTION")
    void opcua_no_connection() {
        when(tagCatalog.byId(1L)).thenReturn(writableOpcUaTag(7L));
        when(opcUaClients.forController(7L)).thenReturn(null);
        CommandOutcome out = service.writeTag(1L, true, "BOOL");
        assertEquals(CommandStatus.FAILED_NO_CONNECTION, out.status);
    }

    @Test
    @DisplayName("Modbus-тег → REJECTED_NOT_WRITABLE даже при writable=true: пишем только по OPC UA и PAC")
    void modbus_is_read_only_regardless_of_flag() {
        when(tagCatalog.byId(1L)).thenReturn(writableModbusTag());
        CommandOutcome out = service.writeTag(1L, 7, "INT");
        assertFalse(out.success);
        assertEquals(CommandStatus.REJECTED_NOT_WRITABLE, out.status);
        verifyNoInteractions(opcUaClients, pac, eventLog);
    }

    @Test
    @DisplayName("writeTagByName: имя не найдено → REJECTED_UNKNOWN_TAG")
    void write_by_name_unknown() {
        when(tagCatalog.byName("nope")).thenReturn(null);
        CommandOutcome out = service.writeTagByName("nope", 1, "INT");
        assertEquals(CommandStatus.REJECTED_UNKNOWN_TAG, out.status);
    }

    private static TagEntity writablePacTag() {
        TagEntity t = new TagEntity();
        t.setName("PAC_DEMO.1V1.ST");
        t.setProtocol("pac");
        t.setNodeId("pac:9001");
        t.setChannelId(9001L);
        t.setDeviceName("1V1");
        t.setFieldName("ST");
        t.setDataType("BOOLEAN");
        t.setWritable(true);
        ControllerEntity c = new ControllerEntity();
        c.setEndpoint("pac://sim:10000");
        t.setController(c);
        return t;
    }

    @Test
    @DisplayName("PAC-запись BOOLEAN → pac.write(host,port,device,field,true) → APPLIED")
    void pac_success_writes_command() {
        when(tagCatalog.byId(1L)).thenReturn(writablePacTag());
        when(pac.write("sim", 10000, "1V1", "ST", true)).thenReturn(true);
        CommandOutcome out = service.writeTag(1L, true, "BOOLEAN");
        assertTrue(out.success);
        assertEquals(CommandStatus.APPLIED, out.status);
        verify(pac).write("sim", 10000, "1V1", "ST", true);   // адрес = device.field, bool → 1/0 в PacLua
    }

    @Test
    @DisplayName("PAC write вернул false (нет соединения опроса) → FAILED_WRITE")
    void pac_write_false_is_failed_write() {
        when(tagCatalog.byId(1L)).thenReturn(writablePacTag());
        when(pac.write(anyString(), anyInt(), anyString(), anyString(), any())).thenReturn(false);
        CommandOutcome out = service.writeTag(1L, true, "BOOLEAN");
        assertFalse(out.success);
        assertEquals(CommandStatus.FAILED_WRITE, out.status);
    }

    @Test
    @DisplayName("PAC-датчик (writable=false) → REJECTED_NOT_WRITABLE, БЕЗ похода в контроллер")
    void pac_not_writable_rejected() {
        TagEntity t = writablePacTag();
        t.setWritable(false);
        when(tagCatalog.byId(1L)).thenReturn(t);
        CommandOutcome out = service.writeTag(1L, true, "BOOLEAN");
        assertEquals(CommandStatus.REJECTED_NOT_WRITABLE, out.status);
        verifyNoInteractions(pac);
    }
}
