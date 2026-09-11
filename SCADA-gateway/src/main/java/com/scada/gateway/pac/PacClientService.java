package com.scada.gateway.pac;

import com.scada.gateway.model.entity.TagEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Клиент PAC (протокол driver-master): пул соединений по host:port, троттлинг логов
 * обрыва — по образцу {@link com.scada.gateway.modbus.ModbusClientService}.
 *
 * <p>Модель протокола «читать всё разом»: один GET_DEVICES_STATES за цикл снимает
 * состояние ВСЕХ устройств контроллера, дальше значения тегов берутся из Lua-таблицы
 * tags по ключу (channelId). Поэтому {@link #read} делает один сетевой запрос на цикл.
 */
@Service
public class PacClientService {

    private static final Logger log = LoggerFactory.getLogger(PacClientService.class);

    /** Таймаут connect/чтения (мс). Явный — как gateway.modbus-op-timeout-ms. */
    @Value("${gateway.pac-op-timeout-ms:3000}")
    private int pacTimeoutMs;

    private final ConcurrentHashMap<String, PacConnection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> failStreak = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastFailLogMs = new ConcurrentHashMap<>();

    private static final long FAIL_LOG_INTERVAL_MS = 30_000; // ≤ 1 WARN / 30 c на endpoint

    /** Одно снятое значение тега. */
    public record Reading(TagEntity tag, Object value) {}

    /**
     * Опросить PAC: один GET_DEVICES_STATES, затем значение каждого тега из tags.
     * При обрыве соединение закрывается и пересоздаётся на следующем цикле; все теги
     * этого цикла получают value=null (quality=BAD на уровне цикла опроса).
     */
    public List<Reading> read(String host, int port, List<TagEntity> tags) {
        String key = host + ":" + port;
        List<Reading> out = new ArrayList<>(tags.size());
        PacConnection conn = connections.computeIfAbsent(key,
                k -> new PacConnection(host, port, pacTimeoutMs));
        try {
            synchronized (conn) {
                if (!conn.isConnected()) conn.connect();
                conn.pollStates();
                for (TagEntity tag : tags) {
                    out.add(new Reading(tag, conn.readValue(String.valueOf(tag.getChannelId()),
                            tag.getDataType())));
                }
            }
            reportOk(key);
        } catch (Exception e) {
            reportFailure(key, e.getMessage());
            conn.close();
            connections.remove(key);       // пересоздадим соединение на следующем цикле
            out.clear();
            for (TagEntity tag : tags) out.add(new Reading(tag, null));
        }
        return out;
    }

    /**
     * Записать значение актуатора (EXEC_DEVICE_COMMAND). Использует УЖЕ открытое
     * соединение опроса (команда идёт по тому же каналу). false — если нет связи/ошибка.
     */
    public boolean write(String host, int port, String device, String field, Object value) {
        PacConnection conn = connections.get(host + ":" + port);
        if (conn == null) {
            log.warn("PAC запись {}.{}: нет активного соединения с {}:{}", device, field, host, port);
            return false;
        }
        try {
            synchronized (conn) {
                conn.writeCommand(device, field, value);
            }
            return true;
        } catch (Exception e) {
            log.warn("PAC запись {}.{} не удалась: {}", device, field, e.getMessage());
            return false;
        }
    }

    /** Закрыть все соединения (вызывается при остановке шлюза). */
    public void disconnectAll() {
        connections.values().forEach(PacConnection::close);
        connections.clear();
    }

    /** Обрыв: первый раз WARN сразу, дальше не чаще раза в 30 c. */
    private void reportFailure(String key, String msg) {
        int s = failStreak.merge(key, 1, Integer::sum);
        long now = System.currentTimeMillis();
        long last = lastFailLogMs.getOrDefault(key, 0L);
        if (s == 1 || now - last >= FAIL_LOG_INTERVAL_MS) {
            lastFailLogMs.put(key, now);
            log.warn("🔴 PAC {} недоступен ({} ошибок подряд): {}", key, s, msg);
        } else {
            log.debug("PAC {} error #{}: {}", key, s, msg);
        }
    }

    /** Успех после ошибок — фиксируем восстановление один раз. */
    private void reportOk(String key) {
        Integer prev = failStreak.put(key, 0);
        lastFailLogMs.remove(key);
        if (prev != null && prev > 0) {
            log.info("🟢 PAC {} на связи (после {} ошибок подряд)", key, prev);
        }
    }
}
