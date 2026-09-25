package com.scada.gateway.ha;

import com.scada.gateway.service.EventLogService;
import jakarta.annotation.PreDestroy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Смена роли — в журнал (event_log) и, если экземпляр стал активным, в Kafka (scada-events):
 * монитор и оператор видят, что произошло переключение и кто теперь ведёт.
 *
 * <p>Пишет в отдельном потоке: событие приходит из потока выборов, а запись в БД может
 * повиснуть (база недоступна — до таймаута пула) — поток выборов не должен ждать.
 */
@Component
public class HaEventRecorder {

    private final EventLogService eventLog;
    private final Leadership leadership;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ha-event-log");
        t.setDaemon(true);
        return t;
    });

    public HaEventRecorder(EventLogService eventLog, Leadership leadership) {
        this.eventLog = eventLog;
        this.leadership = leadership;
    }

    @EventListener
    public void onLeadershipChanged(LeadershipChanged e) {
        if (!leadership.isHaEnabled()) return; // одиночный шлюз — переключений не бывает
        String role = e.active() ? "ACTIVE" : "STANDBY";
        String message = e.active()
                ? "Экземпляр " + e.instanceId() + " стал активным: " + e.reason()
                : "Экземпляр " + e.instanceId() + " перешёл в резерв: " + e.reason();
        writer.execute(() -> eventLog.logEvent("HA", "HotStandby", e.active() ? "INFO" : "WARNING", message,
                Map.of("instance", e.instanceId(), "role", role, "reason", e.reason())));
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
    }
}
