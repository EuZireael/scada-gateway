package com.scada.gateway.modbus;

/**
 * Разбор Modbus endpoint-строки вида {@code "modbus://host:port"} на хост и порт.
 *
 * <p>Выделено из god-класса OpcUaClientServiceDB. Чистые функции без состояния и I/O —
 * тривиально покрываются модульными тестами (см. {@code ModbusEndpointTest}). Оба метода
 * намеренно «безопасные»: при кривой/пустой строке возвращают дефолт, а не бросают —
 * разбор endpoint не должен ронять поток опроса.
 */
public final class ModbusEndpoint {

    private ModbusEndpoint() {
        // Утилитный класс — не инстанцируем.
    }

    /** Хост из {@code "modbus://host:port"}. При ошибке — 127.0.0.1 (безопасный дефолт). */
    public static String host(String endpoint) {
        try {
            String s = endpoint.replace("modbus://", "");
            if (s.contains(":")) {
                return s.split(":")[0];
            }
            return s;
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /** Порт из {@code "modbus://host:port"}. Нет порта или ошибка → defaultPort. */
    public static int port(String endpoint, int defaultPort) {
        try {
            String s = endpoint.replace("modbus://", "");
            String[] parts = s.split(":");
            if (parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
            return defaultPort;
        } catch (Exception e) {
            return defaultPort;
        }
    }
}
