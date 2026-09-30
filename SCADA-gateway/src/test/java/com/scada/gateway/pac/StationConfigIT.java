package com.scada.gateway.pac;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Конфиг реальной станции (config/stations/*.yaml, tools/station_config.sh) против настоящей
 * прошивки: существует ли каждый включённый канал в ПЛК (сверка по driver-master — так же, как
 * его видит OPC UA-фасад ptusa-opcua/, сквозной путь по OPC UA — стенд docker-compose.moika.yml).
 * Каждый включённый канал читается кодом шлюза (PacConnection → снимок t →
 * PacLua.read) и приходит со значением своего dataType. Эталон — эмулятор ptusa с проектом
 * станции (tools/ptusa_emulator.sh), значения там нулевые, но структура снимка — настоящая.
 *
 * <pre>
 *   tools/ptusa_emulator.sh ../Moika/BN1-CIP1 10100
 *   STATION_CONFIG=../config/stations/BN1_MCA1.yaml PAC_IT_HOST=localhost PAC_IT_PORT=10100 \
 *     ./mvnw -Dit.test=StationConfigIT verify
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "STATION_CONFIG", matches = ".+")
class StationConfigIT {

    @Test
    @SuppressWarnings("unchecked")
    void everyEnabledChannelIsReadFromRealFirmware() throws Exception {
        Map<String, Object> root = new Yaml().load(Files.readString(Path.of(System.getenv("STATION_CONFIG"))));
        List<Map<String, Object>> servers =
                (List<Map<String, Object>>) ((Map<String, Object>) root.get("opcua")).get("servers");

        PacConnection conn = new PacConnection(System.getenv().getOrDefault("PAC_IT_HOST", "localhost"),
                Integer.parseInt(System.getenv().getOrDefault("PAC_IT_PORT", "10100")), 5000);
        conn.connect();
        conn.handshake();
        try {
            conn.pollStates();
            int checked = 0;
            Map<String, List<String>> missingByDevice = new TreeMap<>();
            List<String> wrongType = new ArrayList<>();
            for (Map<String, Object> server : servers) {
                for (Map<String, Object> tag : (List<Map<String, Object>>) server.get("tags")) {
                    if (!Boolean.TRUE.equals(tag.get("enabled"))) continue;
                    checked++;
                    String device = (String) tag.get("deviceName");
                    String field = (String) tag.get("fieldName");
                    String dataType = (String) tag.get("dataType");
                    Object value = conn.readValue(device, field, dataType);
                    if (value == null) {
                        missingByDevice.computeIfAbsent(device, d -> new ArrayList<>()).add(field);
                        continue;
                    }
                    Class<?> expected = switch (dataType) {
                        case "FLOAT" -> Double.class;
                        case "STRING" -> String.class;
                        default -> Long.class;
                    };
                    if (!expected.isInstance(value)) wrongType.add(device + "." + field + " (" + dataType + ") = " + value);
                }
            }
            int missing = missingByDevice.values().stream().mapToInt(List::size).sum();
            System.out.printf("STATION IT: читается %d/%d включённых каналов; нет в ПЛК %d, не тот тип %d%n",
                    checked - missing - wrongType.size(), checked, missing, wrongType.size());
            missingByDevice.forEach((d, f) -> System.out.println("  нет: " + d + " " + f.size() + " полей, напр. "
                    + f.subList(0, Math.min(4, f.size()))));
            wrongType.stream().limit(20).forEach(w -> System.out.println("  тип: " + w));
            assertTrue(missing == 0 && wrongType.isEmpty(), "каналы без значения или не того типа — см. вывод");
        } finally {
            conn.close();
        }
    }
}
