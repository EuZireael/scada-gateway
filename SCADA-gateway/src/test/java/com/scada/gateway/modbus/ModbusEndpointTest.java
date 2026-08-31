package com.scada.gateway.modbus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Модульные тесты разбора Modbus endpoint-строки {@link ModbusEndpoint}.
 * Чистые функции → быстрые тесты без Spring/инфраструктуры.
 */
class ModbusEndpointTest {

    // ---------- host ----------

    @Test
    @DisplayName("host: полный endpoint modbus://host:port → host")
    void host_full() {
        assertEquals("192.168.1.10", ModbusEndpoint.host("modbus://192.168.1.10:502"));
    }

    @Test
    @DisplayName("host: без порта → сам host")
    void host_no_port() {
        assertEquals("192.168.1.10", ModbusEndpoint.host("modbus://192.168.1.10"));
    }

    @Test
    @DisplayName("host: без префикса modbus:// тоже парсится")
    void host_no_prefix() {
        assertEquals("wago", ModbusEndpoint.host("wago:5020"));
    }

    @Test
    @DisplayName("host: null → безопасный дефолт 127.0.0.1 (не бросает)")
    void host_null_is_safe() {
        // Кривой вход не должен ронять опрос — отдаём дефолт.
        assertEquals("127.0.0.1", ModbusEndpoint.host(null));
    }

    // ---------- port ----------

    @Test
    @DisplayName("port: полный endpoint → номер порта")
    void port_full() {
        assertEquals(5020, ModbusEndpoint.port("modbus://wago:5020", 502));
    }

    @Test
    @DisplayName("port: нет порта → defaultPort")
    void port_missing_uses_default() {
        assertEquals(502, ModbusEndpoint.port("modbus://wago", 502));
    }

    @Test
    @DisplayName("port: нечисловой порт → defaultPort (не бросает)")
    void port_non_numeric_uses_default() {
        assertEquals(502, ModbusEndpoint.port("modbus://wago:abc", 502));
    }

    @Test
    @DisplayName("port: null → defaultPort (не бросает)")
    void port_null_is_safe() {
        assertEquals(502, ModbusEndpoint.port(null, 502));
    }
}
