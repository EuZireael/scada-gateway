package com.scada.gateway.telemetry;

import com.scada.gateway.model.entity.TagEntity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Что попадает в локальную историю: изменение, качество, «пульс», зона и окно тега. */
class TelemetryHistoryFilterTest {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");

    private static TagEntity tag(long id) {
        TagEntity t = new TagEntity();
        t.setId(id);
        return t;
    }

    /** Часы шлюза, которые тест двигает сам: интервалы считаются по ним, а не по метке источника. */
    private Instant now = T0;
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    /** Умолчания: любое изменение, пульс раз в 10 мин. */
    private final TelemetryHistoryFilter filter = new TelemetryHistoryFilter(0, 0, 0, 600_000, clock);

    /** Опрос в момент {@code T0 + seconds} по часам шлюза; метка источника — застывшая T0. */
    private boolean poll(TagEntity tag, Object value, String quality, long seconds) {
        now = T0.plusSeconds(seconds);
        return filter.shouldPersist(tag, value, quality, T0);
    }

    @Test
    void unchanged_value_is_skipped_until_heartbeat_but_change_and_quality_are_written() {
        TagEntity tag = tag(1);
        assertTrue(poll(tag, 5.0, "GOOD", 0), "первая точка");
        assertFalse(poll(tag, 5.0, "GOOD", 2), "то же значение");
        assertTrue(poll(tag, 5.1, "GOOD", 4), "изменилось");
        assertTrue(poll(tag, 5.1, "BAD", 6), "сменилось качество");
        assertFalse(poll(tag, 5.1, "BAD", 8));
        assertTrue(poll(tag, 5.1, "BAD", 6 + 600), "пульс по часам шлюза, хотя метка источника застыла");
    }

    /** Настройки тега: зона 1.0 копит дрейф от последней ЗАПИСАННОЙ точки, окно 60 с режет частые. */
    @Test
    void per_tag_deadband_accumulates_drift_and_min_interval_defers_change() {
        TagEntity tag = tag(2);
        tag.setHistoryDeadband(1.0);
        tag.setHistoryMinIntervalMs(60_000L);
        assertTrue(poll(tag, 10.0, "GOOD", 0));
        assertFalse(poll(tag, 10.6, "GOOD", 70), "внутри зоны");
        assertTrue(poll(tag, 11.2, "GOOD", 80), "дрейф накопился до 1.2");
        assertFalse(poll(tag, 20.0, "GOOD", 90), "окно 60 с ещё не прошло");
        assertTrue(poll(tag, 20.0, "GOOD", 141), "изменение дописано после окна");
    }
}
