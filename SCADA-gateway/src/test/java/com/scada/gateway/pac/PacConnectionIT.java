package com.scada.gateway.pac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Живой E2E против ЗАПУЩЕННОГО PAC-симулятора
 * ({@code python simulator.py config/pac_demo.yaml}). В CI пропускается — включается
 * переменной окружения PAC_IT_HOST:
 * <pre>
 *   PAC_IT_HOST=localhost PAC_IT_PORT=10000 ./mvnw -Dtest=PacConnectionIT test
 * </pre>
 * Проверяет полный путь клиента: connect → handshake (v104) → pollStates (zlib+Lua) →
 * readValue по channelId (демо: 9001 = bool ST клапана, 9007 = float обороты мотора).
 */
@EnabledIfEnvironmentVariable(named = "PAC_IT_HOST", matches = ".+")
class PacConnectionIT {

    @Test
    void connectsPollsAndReads() throws Exception {
        String host = System.getenv("PAC_IT_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault("PAC_IT_PORT", "10000"));

        PacConnection conn = new PacConnection(host, port, 3000);
        try {
            conn.connect();
            conn.handshake();
            conn.pollStates();

            Object st = conn.readValue("9001", "BOOLEAN");   // 1V1.ST — bool
            Object rpm = conn.readValue("9007", "FLOAT");    // 1M1.V  — float (обороты)

            System.out.println("PAC IT: 9001(ST)=" + st + ", 9007(V)=" + rpm);
            assertTrue(st instanceof Boolean, "ST должен быть boolean");
            assertNotNull(rpm, "9007 должно прийти из снимка состояний");
            assertTrue(rpm instanceof Double, "V должен быть double");
            assertTrue((Double) rpm > 1000.0, "обороты мотора в разумном диапазоне");
        } finally {
            conn.close();
        }
    }

    @Test
    void writeCommandApplies() throws Exception {
        String host = System.getenv("PAC_IT_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault("PAC_IT_PORT", "10000"));

        PacConnection conn = new PacConnection(host, port, 3000);
        try {
            conn.connect();
            conn.handshake();
            conn.writeCommand("1V1", "ST", true);   // EXEC_DEVICE_COMMAND: __1V1:set_cmd('ST',1,1)

            // Снимок состояний сим обновляет раз в update_rate (~0.5с), поэтому читаем
            // с ретраями: команда применилась в теге, но в tags[] попадёт со след. циклом.
            Object st = null;
            for (int i = 0; i < 12 && !Boolean.TRUE.equals(st); i++) {
                Thread.sleep(300);
                conn.pollStates();
                st = conn.readValue("9001", "BOOLEAN");
            }
            System.out.println("PAC IT write: 1V1.ST после записи = " + st);
            assertEquals(Boolean.TRUE, st, "после записи ST должен стать true");
        } finally {
            conn.close();
        }
    }
}
