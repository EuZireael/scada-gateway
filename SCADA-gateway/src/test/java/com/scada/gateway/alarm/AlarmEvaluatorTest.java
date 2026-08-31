package com.scada.gateway.alarm;

import com.scada.gateway.kafka.producer.AlarmProducer;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.service.EventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Тесты edge-триггера алармов на моках AlarmProducer/EventLogService.
 * Проверяем: аларм шлётся ОДИН раз при выходе за предел, не спамит пока нарушение
 * держится, и закрывается (cleared=true) при возврате в норму.
 */
@ExtendWith(MockitoExtension.class)
class AlarmEvaluatorTest {

    @Mock AlarmProducer alarmProducer;
    @Mock EventLogService eventLog;

    AlarmEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AlarmEvaluator(alarmProducer, eventLog);
    }

    private static TagEntity tag(Double min, Double max) {
        TagEntity t = new TagEntity();
        t.setId(1L);
        t.setName("temp");
        t.setUnit("C");
        t.setMinValue(min);
        t.setMaxValue(max);
        return t;
    }

    @Test
    @DisplayName("Нет порогов (min/max=null) → аларм не считается")
    void no_thresholds_no_alarm() {
        evaluator.evaluate(tag(null, null), 999);
        verifyNoInteractions(alarmProducer);
    }

    @Test
    @DisplayName("Значение в пределах → аларма нет")
    void within_range_no_alarm() {
        evaluator.evaluate(tag(0.0, 100.0), 50);
        verifyNoInteractions(alarmProducer);
    }

    @Test
    @DisplayName("Выход выше max → аларм (cleared=false) один раз")
    void above_max_raises_alarm() {
        evaluator.evaluate(tag(0.0, 100.0), 150);
        verify(alarmProducer).sendAlarm(any(TagEntity.class), anyString(), anyString(),
                anyString(), anyDouble(), anyDouble(), eq(false));
    }

    @Test
    @DisplayName("Пока нарушение держится — повторные алармы НЕ шлются (edge-триггер)")
    void sustained_breach_fires_once() {
        TagEntity t = tag(0.0, 100.0);
        evaluator.evaluate(t, 150);
        evaluator.evaluate(t, 160);   // всё ещё HIGH — новый аларм не нужен
        verify(alarmProducer, times(1)).sendAlarm(any(TagEntity.class), anyString(), anyString(),
                anyString(), anyDouble(), anyDouble(), eq(false));
    }

    @Test
    @DisplayName("Возврат в норму → закрытие эпизода (cleared=true)")
    void return_to_normal_clears() {
        TagEntity t = tag(0.0, 100.0);
        evaluator.evaluate(t, 150);   // нарушение
        evaluator.evaluate(t, 50);    // норма (с запасом > deadband)
        verify(alarmProducer).sendAlarm(any(TagEntity.class), anyString(), anyString(),
                anyString(), anyDouble(), anyDouble(), eq(true));
    }
}
