package com.scada.gateway.telemetry;

import com.scada.gateway.model.entity.TagEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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

    /** Умолчания: любое изменение, пульс раз в 10 мин. */
    private final TelemetryHistoryFilter filter = new TelemetryHistoryFilter(0, 0, 0, 600_000);

    @Test
    void unchanged_value_is_skipped_until_heartbeat_but_change_and_quality_are_written() {
        TagEntity tag = tag(1);
        assertTrue(filter.shouldPersist(tag, 5.0, "GOOD", T0), "первая точка");
        assertFalse(filter.shouldPersist(tag, 5.0, "GOOD", T0.plusSeconds(2)), "то же значение");
        assertTrue(filter.shouldPersist(tag, 5.1, "GOOD", T0.plusSeconds(4)), "изменилось");
        assertTrue(filter.shouldPersist(tag, 5.1, "BAD", T0.plusSeconds(6)), "сменилось качество");
        assertFalse(filter.shouldPersist(tag, 5.1, "BAD", T0.plusSeconds(8)));
        assertTrue(filter.shouldPersist(tag, 5.1, "BAD", T0.plusSeconds(6 + 600)), "пульс");
    }

    /** Настройки тега: зона 1.0 копит дрейф от последней ЗАПИСАННОЙ точки, окно 60 с режет частые. */
    @Test
    void per_tag_deadband_accumulates_drift_and_min_interval_defers_change() {
        TagEntity tag = tag(2);
        tag.setHistoryDeadband(1.0);
        tag.setHistoryMinIntervalMs(60_000L);
        assertTrue(filter.shouldPersist(tag, 10.0, "GOOD", T0));
        assertFalse(filter.shouldPersist(tag, 10.6, "GOOD", T0.plusSeconds(70)), "внутри зоны");
        assertTrue(filter.shouldPersist(tag, 11.2, "GOOD", T0.plusSeconds(80)), "дрейф накопился до 1.2");
        assertFalse(filter.shouldPersist(tag, 20.0, "GOOD", T0.plusSeconds(90)), "окно 60 с ещё не прошло");
        assertTrue(filter.shouldPersist(tag, 20.0, "GOOD", T0.plusSeconds(141)), "изменение дописано после окна");
    }
}
