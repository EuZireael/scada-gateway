package com.scada.gateway.telemetry;

import com.scada.gateway.model.entity.TagEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Отправка телеметрии в Kafka по исключению (с 02.10.2026). Раньше каждый тег уходил каждый
 * цикл опроса: на 10 проектах «Нормализации» 13,7 тыс. сообщений/с, почти все — повтор того же
 * значения, и монитор (runtime) тратил на их разбор и раздачу основную часть работы.
 *
 * <p>Значение уходит, если: первое после старта шлюза; сменилось качество; значение вышло за
 * зону нечувствительности ({@code gateway.publish.deadband}/{@code deadband-percent}, 0 — любое
 * отличие); прошло {@code gateway.publish.full-resend-ms} с последней отправки тега — «полная
 * отправка», по ней потребитель знает, что тег жив, и после своего перезапуска получает все
 * значения за этот период. Логика — та же, что у локальной истории ({@link TelemetryHistoryFilter}),
 * но со своими настройками и без поканальных {@code history:}-переопределений.
 *
 * <p>Договорённость с runtime: {@code runtime.telemetry.max-silence-ms} должен быть больше
 * full-resend-ms (по умолчанию 40 с против 30 с). {@code gateway.publish.enabled=false} —
 * прежнее поведение, каждый тег каждый цикл.
 */
@Component
public class TelemetryPublishFilter {

    private final boolean enabled;
    private final TelemetryHistoryFilter filter;

    @Autowired
    public TelemetryPublishFilter(
            @Value("${gateway.publish.enabled:true}") boolean enabled,
            @Value("${gateway.publish.deadband:0}") double deadband,
            @Value("${gateway.publish.deadband-percent:0}") double deadbandPercent,
            @Value("${gateway.publish.min-interval-ms:0}") long minIntervalMs,
            @Value("${gateway.publish.full-resend-ms:30000}") long fullResendMs) {
        this.enabled = enabled;
        this.filter = new TelemetryHistoryFilter(deadband, deadbandPercent, minIntervalMs, fullResendMs,
                Clock.systemUTC(), false);
    }

    /** Для тестов: включён, свои часы. */
    TelemetryPublishFilter(double deadband, double deadbandPercent, long minIntervalMs, long fullResendMs, Clock clock) {
        this.enabled = true;
        this.filter = new TelemetryHistoryFilter(deadband, deadbandPercent, minIntervalMs, fullResendMs, clock, false);
    }

    /** Прежнее поведение — каждый тег каждый цикл. */
    static TelemetryPublishFilter disabled() {
        return new TelemetryPublishFilter(false, 0, 0, 0, 0);
    }

    /** true — значение отправлять; решение сразу запоминается как «отправлено». */
    public boolean shouldPublish(TagEntity tag, Object value, String quality, Instant timestamp) {
        return !enabled || filter.shouldPersist(tag, value, quality, timestamp);
    }
}
