package com.scada.gateway.pac;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Разбор PAC endpoint-строки "pac://host:port". */
class PacEndpointTest {

    @Test
    void parsesHostAndPort() {
        assertEquals("192.168.0.10", PacEndpoint.host("pac://192.168.0.10:10000"));
        assertEquals(10000, PacEndpoint.port("pac://192.168.0.10:10000", 10000));
        assertEquals(12345, PacEndpoint.port("pac://simulator:12345", 10000));
    }

    @Test
    void portFallsBackToDefault() {
        assertEquals(10000, PacEndpoint.port("pac://simulator", 10000));
        assertEquals("simulator", PacEndpoint.host("pac://simulator"));
    }
}
