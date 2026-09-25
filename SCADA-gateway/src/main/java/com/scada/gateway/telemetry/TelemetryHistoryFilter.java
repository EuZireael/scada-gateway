package com.scada.gateway.telemetry;

import com.scada.gateway.model.entity.TagEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Решает, писать ли снятое значение в локальную историю (таблицу telemetry). В Kafka значения
 * уходят независимо от этого решения — фильтр касается только БД шлюза.
 *
 * <p>Раньше в историю шла каждая точка каждого цикла опроса: на стенде 25.09.2026 это 10 млн
 * строк в час (5260 тегов, опрос ~2 с), из которых значение или качество менялось у 41 тыс. —
 * 97 ГБ за месяц, которые никто не читал.
 *
 * <p>Точка пишется, если (по порядку):
 * <ol>
 *   <li>это первая точка тега после старта шлюза;</li>
 *   <li>сменилось качество (GOOD↔BAD) — обрыв связи должен остаться в истории;</li>
 *   <li>с последней записанной точки прошло {@code maxInterval} — «пульс»: видно, что тег жив,
 *       а значение просто стоит;</li>
 *   <li>иначе, если с последней записи не прошло {@code minInterval}, — нет (частые мелкие
 *       колебания режутся по времени);</li>
 *   <li>значение отличается от последнего записанного больше зоны нечувствительности
 *       ({@code max(deadband, |прежнее| · deadbandPercent / 100)}); при нулевой зоне — любое
 *       отличие. Нечисловые значения сравниваются на равенство.</li>
 * </ol>
 * Сравнение идёт с последним ЗАПИСАННЫМ значением, а не с предыдущим опросом: медленный дрейф
 * внутри зоны нечувствительности не теряется, а накапливается до порога. Изменение, отрезанное
 * {@code minInterval}, тоже не теряется — оно запишется первым же опросом после окна, если
 * значение так и не вернулось.
 *
 * <p>Умолчания — {@code gateway.history.*}; тег переопределяет любое из четырёх полей блоком
 * {@code history:} в controllers.yaml (колонки {@code tags.history_*}, {@code null} = умолчание).
 * 0 у интервала — выключено.
 */
@Component
public class TelemetryHistoryFilter {

    private final double defaultDeadband;
    private final double defaultDeadbandPercent;
    private final long defaultMinIntervalMs;
    private final long defaultMaxIntervalMs;

    private final Map<Long, Written> lastWritten = new ConcurrentHashMap<>();

    public TelemetryHistoryFilter(
            @Value("${gateway.history.deadband:0}") double defaultDeadband,
            @Value("${gateway.history.deadband-percent:0}") double defaultDeadbandPercent,
            @Value("${gateway.history.min-interval-ms:0}") long defaultMinIntervalMs,
            @Value("${gateway.history.max-interval-ms:600000}") long defaultMaxIntervalMs) {
        this.defaultDeadband = defaultDeadband;
        this.defaultDeadbandPercent = defaultDeadbandPercent;
        this.defaultMinIntervalMs = defaultMinIntervalMs;
        this.defaultMaxIntervalMs = defaultMaxIntervalMs;
    }

    /** true — точку писать; решение сразу запоминается как «записано». */
    public boolean shouldPersist(TagEntity tag, Object value, String quality, Instant timestamp) {
        boolean[] persist = {false};
        lastWritten.compute(tag.getId(), (id, last) -> {
            if (last == null || decide(tag, last, value, quality, timestamp)) {
                persist[0] = true;
                return new Written(value, quality, timestamp);
            }
            return last;
        });
        return persist[0];
    }

    private boolean decide(TagEntity tag, Written last, Object value, String quality, Instant timestamp) {
        if (!Objects.equals(last.quality(), quality)) {
            return true;
        }
        long elapsedMs = timestamp.toEpochMilli() - last.at().toEpochMilli();
        long maxIntervalMs = orDefault(tag.getHistoryMaxIntervalMs(), defaultMaxIntervalMs);
        if (maxIntervalMs > 0 && elapsedMs >= maxIntervalMs) {
            return true;
        }
        long minIntervalMs = orDefault(tag.getHistoryMinIntervalMs(), defaultMinIntervalMs);
        if (minIntervalMs > 0 && elapsedMs < minIntervalMs) {
            return false;
        }
        return changed(tag, last.value(), value);
    }

    private boolean changed(TagEntity tag, Object previous, Object current) {
        if (previous instanceof Number p && current instanceof Number c) {
            double before = p.doubleValue();
            double delta = Math.abs(c.doubleValue() - before);
            double deadband = Math.max(
                    orDefault(tag.getHistoryDeadband(), defaultDeadband),
                    Math.abs(before) * orDefault(tag.getHistoryDeadbandPercent(), defaultDeadbandPercent) / 100.0);
            return deadband > 0 ? delta > deadband : delta != 0;
        }
        return !Objects.equals(previous, current);
    }

    private static double orDefault(Double value, double fallback) {
        return value != null ? value : fallback;
    }

    private static long orDefault(Long value, long fallback) {
        return value != null ? value : fallback;
    }

    private record Written(Object value, String quality, Instant at) {
    }
}
