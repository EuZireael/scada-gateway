package com.scada.gateway.model;

import com.scada.gateway.model.entity.TagEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты классификации протокола тега. Строим TagEntity сеттерами — без Spring/БД.
 * Отдельно проверяем «мягкий» фолбэк по nodeId/modbusAddress, который отличает эти
 * функции от встроенных TagEntity.isOpcUa()/isModbus().
 */
class TagProtocolsTest {

    private static TagEntity tag(String protocol, String nodeId, Integer modbusAddress) {
        TagEntity t = new TagEntity();
        t.setProtocol(protocol);
        t.setNodeId(nodeId);
        t.setModbusAddress(modbusAddress);
        return t;
    }

    // ---------- isOpcUaTag ----------

    @Test
    @DisplayName("isOpcUaTag: protocol=opcua (регистр не важен) → true")
    void opcua_by_protocol() {
        assertTrue(TagProtocols.isOpcUaTag(tag("opcua", null, null)));
        assertTrue(TagProtocols.isOpcUaTag(tag("OPCUA", null, null)));
    }

    @Test
    @DisplayName("isOpcUaTag: protocol не задан, но есть nodeId → true (фолбэк)")
    void opcua_by_nodeid_fallback() {
        assertTrue(TagProtocols.isOpcUaTag(tag(null, "ns=2;s=Tag", null)));
    }

    @Test
    @DisplayName("isOpcUaTag: protocol=modbus → false, даже если есть nodeId")
    void opcua_modbus_guard_wins() {
        assertFalse(TagProtocols.isOpcUaTag(tag("modbus", "ns=2;s=Tag", null)));
    }

    @Test
    @DisplayName("isOpcUaTag: ни protocol, ни nodeId → false")
    void opcua_none() {
        assertFalse(TagProtocols.isOpcUaTag(tag(null, null, null)));
        assertFalse(TagProtocols.isOpcUaTag(tag(null, "", null)));
    }

    // ---------- isModbusTag ----------

    @Test
    @DisplayName("isModbusTag: protocol=modbus (регистр не важен) → true")
    void modbus_by_protocol() {
        assertTrue(TagProtocols.isModbusTag(tag("modbus", null, null)));
        assertTrue(TagProtocols.isModbusTag(tag("MODBUS", null, null)));
    }

    @Test
    @DisplayName("isModbusTag: protocol не задан, но есть modbusAddress>0 → true (фолбэк)")
    void modbus_by_address_fallback() {
        assertTrue(TagProtocols.isModbusTag(tag(null, null, 40001)));
    }

    @Test
    @DisplayName("isModbusTag: адрес 0/null или иной protocol → false")
    void modbus_none() {
        assertFalse(TagProtocols.isModbusTag(tag("opcua", null, null)));
        assertFalse(TagProtocols.isModbusTag(tag(null, null, 0)));
        assertFalse(TagProtocols.isModbusTag(tag(null, null, null)));
    }
}
