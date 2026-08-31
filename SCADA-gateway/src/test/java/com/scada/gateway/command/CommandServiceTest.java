package com.scada.gateway.command;

import com.scada.gateway.modbus.ModbusClientService;
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
 * Тесты сервиса команд на МОКАХ портов (TagCatalog, OpcUaClientRegistry) и Modbus.
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
    @Mock ModbusClientService modbus;
    @Mock EventLogService eventLog;

    CommandService service;

    @BeforeEach
    void setUp() {
        service = new CommandService(tagCatalog, opcUaClients, modbus, eventLog, 5000L);
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
        TagEntity t = writableModbusTag();
        t.setWritable(false);
        when(tagCatalog.byId(1L)).thenReturn(t);
        CommandOutcome out = service.writeTag(1L, 5, "INT");
        assertEquals(CommandStatus.REJECTED_NOT_WRITABLE, out.status);
        verifyNoInteractions(modbus);   // защита сработала ДО записи
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
    @DisplayName("Modbus-запись INT → writeRegister(host,port,addr,unit,value)")
    void modbus_success_writes_register() throws Exception {
        when(tagCatalog.byId(1L)).thenReturn(writableModbusTag());
        CommandOutcome out = service.writeTag(1L, 7, "INT");
        assertTrue(out.success);
        assertEquals(CommandStatus.APPLIED, out.status);
        verify(modbus).writeRegister("wago", 5020, 40001, 1, 7);
    }

    @Test
    @DisplayName("Modbus IOException → FAILED_NO_CONNECTION")
    void modbus_io_error_is_no_connection() throws Exception {
        when(tagCatalog.byId(1L)).thenReturn(writableModbusTag());
        doThrow(new java.io.IOException("нет связи"))
                .when(modbus).writeRegister(anyString(), anyInt(), anyInt(), anyInt(), anyInt());
        CommandOutcome out = service.writeTag(1L, 7, "INT");
        assertEquals(CommandStatus.FAILED_NO_CONNECTION, out.status);
    }

    @Test
    @DisplayName("writeTagByName: имя не найдено → REJECTED_UNKNOWN_TAG")
    void write_by_name_unknown() {
        when(tagCatalog.byName("nope")).thenReturn(null);
        CommandOutcome out = service.writeTagByName("nope", 1, "INT");
        assertEquals(CommandStatus.REJECTED_UNKNOWN_TAG, out.status);
    }
}
