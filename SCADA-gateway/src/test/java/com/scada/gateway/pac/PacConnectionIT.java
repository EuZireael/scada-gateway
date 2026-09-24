package com.scada.gateway.pac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Живой E2E против ЗАПУЩЕННОГО PAC. В CI пропускается — включается переменной окружения
 * PAC_IT_HOST. Годится любой из двух PAC:
 * <ul>
 *   <li>симулятор стенда ({@code ./up.sh}, порт 10000) — значения из архива;</li>
 *   <li>эмулятор ptusa — настоящая прошивка с проектом станции BN1-МСА1
 *       ({@code tools/ptusa_emulator.sh <папка проекта>}, порт 10100), значения нулевые.</li>
 * </ul>
 * <pre>
 *   PAC_IT_HOST=localhost PAC_IT_PORT=10000 ./mvnw -Dtest=PacConnectionIT test
 * </pre>
 * Проверяет полный путь клиента: connect (приветствие) → handshake (v104) → pollStates
 * (zlib+Lua) → readValue по t[прибор][поле], а также что каждый PAC-тег из
 * controllers.yaml есть в снимке. Приборы: LINE1V0 (клапан: ST — состояние, M — режим,
 * чистый актуатор), LINE1M1.RPM (обороты мотора), LINE1FS1.P_DT (уставка задержки).
 */
@EnabledIfEnvironmentVariable(named = "PAC_IT_HOST", matches = ".+")
class PacConnectionIT {

    /** Конфиг шлюза из main: в test-classpath лежит свой controllers.yaml, поэтому по пути. */
    private static final Path CONTROLLERS_YAML = Path.of("src/main/resources/controllers.yaml");
    private static final String PAC_CONTROLLER_ID = "pac-demo-001";

    @Test
    void connectsPollsAndReads() throws Exception {
        PacConnection conn = open();
        try {
            conn.pollStates();

            Object st = conn.readValue("LINE1V0", "ST", "INT32");
            Object rpm = conn.readValue("LINE1M1", "RPM", "FLOAT");
            Object pdt = conn.readValue("LINE1FS1", "P_DT", "FLOAT");

            System.out.println("PAC IT: LINE1V0.ST=" + st + ", LINE1M1.RPM=" + rpm + ", LINE1FS1.P_DT=" + pdt);
            assertInstanceOf(Long.class, st, "ST — целый (INT32)");
            assertInstanceOf(Double.class, rpm, "RPM — вещественный (FLOAT)");
            double rpmValue = (Double) rpm;
            assertTrue(rpmValue >= 0.0 && rpmValue <= 2950.0, "обороты в пределах мотора: " + rpm);
            assertInstanceOf(Double.class, pdt, "P_DT — вещественный (FLOAT)");
        } finally {
            conn.close();
        }
    }

    /**
     * Сверка конфига шлюза с контроллером: каждый PAC-тег из controllers.yaml приходит в
     * снимке состояний и приводится к своему dataType. Расхождение раскладки иначе
     * проявится только BAD-качеством у оператора.
     */
    @Test
    void everyConfiguredPacChannelIsInSnapshot() throws Exception {
        List<Map<String, Object>> tags = configuredPacTags();
        assertFalse(tags.isEmpty(), "в controllers.yaml нет тегов контроллера " + PAC_CONTROLLER_ID);

        PacConnection conn = open();
        try {
            conn.pollStates();
            List<String> problems = new ArrayList<>();
            for (Map<String, Object> tag : tags) {
                String device = String.valueOf(tag.get("deviceName"));
                String field = String.valueOf(tag.get("fieldName"));
                String dataType = String.valueOf(tag.get("dataType"));
                Object value = conn.readValue(device, field, dataType);
                Class<?> expected = "FLOAT".equals(dataType) ? Double.class : Long.class;
                if (!expected.isInstance(value)) {
                    problems.add(device + "." + field + " (" + dataType + ") = " + value);
                }
            }
            System.out.println("PAC IT: в снимке " + (tags.size() - problems.size()) + "/" + tags.size() + " каналов");
            assertTrue(problems.isEmpty(), "каналы без значения нужного типа: " + problems);
        } finally {
            conn.close();
        }
    }

    @Test
    void writeCommandApplies() throws Exception {
        PacConnection conn = open();
        try {
            // LINE1V0.M — чистый актуатор (без источника данных): значение целиком за
            // оператором, контроллер его не перетирает. Пишем противоположное текущему и в
            // конце возвращаем как было, чтобы не оставлять стенд в ручном режиме.
            conn.pollStates();
            Object before = conn.readValue("LINE1V0", "M", "INT32");
            assertInstanceOf(Long.class, before, "M — целый (INT32)");
            long target = ((Long) before) == 0L ? 1L : 0L;

            try {
                conn.writeCommand("LINE1V0", "M", target);   // __LINE1V0:set_cmd('M', 1, <target>)
                Object m = awaitValue(conn, "LINE1V0", "M", target);
                System.out.println("PAC IT write: LINE1V0.M " + before + " → " + m);
                assertEquals(target, m, "после записи M должен стать " + target);
            } finally {
                conn.writeCommand("LINE1V0", "M", before);
                awaitValue(conn, "LINE1V0", "M", before);
            }

            // Команда несуществующему прибору: PAC отвечает кодом ошибки, а не успехом.
            assertThrows(java.io.IOException.class, () -> conn.writeCommand("NO_SUCH_DEVICE", "M", 1L));
        } finally {
            conn.close();
        }
    }

    private static PacConnection open() throws Exception {
        String host = System.getenv("PAC_IT_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault("PAC_IT_PORT", "10000"));
        PacConnection conn = new PacConnection(host, port, 3000);
        conn.connect();
        conn.handshake();
        return conn;
    }

    /**
     * Снимок состояний симулятор обновляет раз в update_rate (~0.5с): команда применилась в
     * теге, но в снимок попадёт со следующим циклом — поэтому читаем с ретраями.
     */
    private static Object awaitValue(PacConnection conn, String device, String field, Object expected)
            throws Exception {
        Object value = null;
        for (int i = 0; i < 12 && !expected.equals(value); i++) {
            Thread.sleep(300);
            conn.pollStates();
            value = conn.readValue(device, field, "INT32");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> configuredPacTags() throws Exception {
        try (Reader in = Files.newBufferedReader(CONTROLLERS_YAML, StandardCharsets.UTF_8)) {
            Map<String, Object> root = new Yaml().load(in);
            List<Map<String, Object>> servers =
                    (List<Map<String, Object>>) ((Map<String, Object>) root.get("opcua")).get("servers");
            return servers.stream()
                    .filter(s -> PAC_CONTROLLER_ID.equals(s.get("id")))
                    .findFirst()
                    .map(s -> (List<Map<String, Object>>) s.get("tags"))
                    .orElse(List.of());
        }
    }
}
