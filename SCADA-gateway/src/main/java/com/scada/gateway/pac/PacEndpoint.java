package com.scada.gateway.pac;

/**
 * Разбор PAC endpoint-строки {@code "pac://host:port"} на хост и порт (аналог
 * {@link com.scada.gateway.modbus.ModbusEndpoint}). Чистые функции без состояния —
 * тривиально тестируются. При кривой строке возвращают безопасный дефолт, а не бросают:
 * разбор endpoint не должен ронять поток опроса.
 */
public final class PacEndpoint {

    private PacEndpoint() {
        // Утилитный класс — не инстанцируем.
    }

    /** Хост из {@code "pac://host:port"}. При ошибке — 127.0.0.1. */
    public static String host(String endpoint) {
        try {
            String s = endpoint.replace("pac://", "");
            return s.contains(":") ? s.split(":")[0] : s;
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /** Порт из {@code "pac://host:port"}. Нет порта или ошибка → defaultPort. */
    public static int port(String endpoint, int defaultPort) {
        try {
            String s = endpoint.replace("pac://", "");
            String[] parts = s.split(":");
            return parts.length > 1 ? Integer.parseInt(parts[1]) : defaultPort;
        } catch (Exception e) {
            return defaultPort;
        }
    }
}
