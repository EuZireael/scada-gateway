package com.scada.gateway.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Периодически шлёт системное событие HEARTBEAT в журнал событий и в Kafka.
 *
 * Назначение — «оживить» журнал событий на демонстрации: реальные события
 * (подключение/ошибки/смена качества) эпизодические, а телеметрия течёт
 * постоянно. Heartbeat даёт ровный поток системных событий, подтверждающий,
 * что шлюз жив, и наполняет вкладку событий на стороне Monitor Srv.
 */
/**
 * Периодический heartbeat шлюза (@Scheduled): регулярный признак «жив», чтобы
 * монитор отличал «шлюз работает» от «шлюз молчит/упал».
 */
@Service
public class GatewayHeartbeatService {

    private final EventLogService eventLogService;
    private final ConfigurationService configurationService;
    private final Instant startedAt = Instant.now();

    public GatewayHeartbeatService(EventLogService eventLogService,
                                   ConfigurationService configurationService) {
        this.eventLogService = eventLogService;
        this.configurationService = configurationService;
    }

    @Scheduled(fixedDelayString = "${gateway.heartbeat-interval-ms:30000}", initialDelay = 15000)
    public void heartbeat() {
        long uptimeSec = Duration.between(startedAt, Instant.now()).getSeconds();

        Map<String, Object> details = new HashMap<>();
        details.put("uptimeSeconds", uptimeSec);
        details.put("controllers", configurationService.getAllControllers().size());
        details.put("tags", configurationService.getAllActiveTags().size());

        eventLogService.logEvent("HEARTBEAT", "Gateway", "INFO",
                "Gateway alive, uptime " + uptimeSec + "s", details);
    }
}