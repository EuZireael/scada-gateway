package com.scada.gateway.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Периодический heartbeat шлюза (@Scheduled): шлёт системное событие HEARTBEAT в
 * журнал событий и в Kafka — регулярный признак «жив», чтобы монитор отличал
 * «шлюз работает» от «шлюз молчит/упал».
 *
 * <p>Также «оживляет» журнал на демонстрации: реальные события (подключение,
 * ошибки, смена качества) эпизодические, а heartbeat даёт ровный фоновый поток и
 * наполняет вкладку событий на стороне Monitor Srv.
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

    /**
     * Раз в heartbeat-interval-ms шлёт событие HEARTBEAT с аптаймом и текущими
     * счётчиками контроллеров/тегов. initialDelay даёт шлюзу подняться до первого удара.
     */
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