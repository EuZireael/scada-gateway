package com.scada.gateway.telemetry;

import com.scada.gateway.alarm.AlarmEvaluator;
import com.scada.gateway.kafka.producer.TelemetryProducer;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.repository.TelemetryRepository;
import com.scada.gateway.service.EventLogService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Отправка в Kafka по исключению (02.10.2026): шлюз слал каждый тег каждый цикл опроса —
 * на 10 проектах «Нормализации» 13,7 тыс. сообщений/с, почти все повтор. Теперь — изменение,
 * смена качества и полная отправка раз в full-resend-ms.
 */
class TelemetryPublishFilterTest {

    private final TelemetryProducer producer = mock(TelemetryProducer.class);
    private final MutableClock clock = new MutableClock();

    private TelemetryProcessor processor() {
        return new TelemetryProcessor(producer, mock(TelemetryRepository.class), mock(EventLogService.class),
                mock(AlarmEvaluator.class), new TelemetryHistoryFilter(0, 0, 0, 600_000),
                new TelemetryPublishFilter(0, 0, 0, 30_000, clock), new SimpleMeterRegistry());
    }

    private static TagEntity tag() {
        TagEntity t = new TagEntity();
        t.setId(1L);
        t.setName("LOAD-01.T.V");
        return t;
    }

    @Test
    @DisplayName("тот же value второй раз не уходит; смена значения уходит сразу")
    void unchangedValueIsNotRepublished() {
        TelemetryProcessor p = processor();
        TagEntity t = tag();
        p.processTagValue(t, 42, "GOOD", Instant.now(), null);
        p.processTagValue(t, 42, "GOOD", Instant.now(), null);
        p.processTagValue(t, 43, "GOOD", Instant.now(), null);

        verify(producer, times(1)).sendTelemetry(any(TagEntity.class), eq(42), eq("GOOD"), any(Instant.class));
        verify(producer, times(1)).sendTelemetry(any(TagEntity.class), eq(43), eq("GOOD"), any(Instant.class));
    }

    @Test
    @DisplayName("неизменившееся значение повторяется раз в full-resend-ms — потребитель знает, что тег жив")
    void fullResendRepublishesUnchanged() {
        TelemetryProcessor p = processor();
        TagEntity t = tag();
        p.processTagValue(t, 42, "GOOD", Instant.now(), null);
        clock.advance(30_000);
        p.processTagValue(t, 42, "GOOD", Instant.now(), null);

        verify(producer, times(2)).sendTelemetry(any(TagEntity.class), eq(42), eq("GOOD"), any(Instant.class));
    }

    static final class MutableClock extends Clock {
        private long ms = 1_000_000;

        void advance(long d) {
            ms += d;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(ms);
        }
    }
}
