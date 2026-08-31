package com.scada.gateway.alarm;

import com.scada.gateway.kafka.producer.AlarmProducer;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.service.EventLogService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Edge-триггерная оценка порогов тега и генерация алармов.
 *
 * <p>Выделено из god-класса OpcUaClientServiceDB. Состояние активных эпизодов
 * ({@code activeAlarmByTag}) принадлежит ТОЛЬКО алармам, поэтому уехало вместе с
 * логикой — общего состояния с другими обязанностями нет (в отличие от команд).
 * Вызывается из горячего пути обработки значения, и только когда алармы включены
 * (gateway.alarms.enabled=true) — сам гейт остаётся на стороне вызывающего. Логика
 * перенесена один-в-один; в тестах AlarmProducer/EventLogService подменяются моками.
 */
@Component
public class AlarmEvaluator {

    private final AlarmProducer alarmProducer;
    private final EventLogService eventLog;

    /** Активный (ещё не закрытый) аларм по тегу: стабильный alarmId на весь эпизод. */
    private final Map<Long, ActiveAlarm> activeAlarmByTag = new ConcurrentHashMap<>();

    public AlarmEvaluator(AlarmProducer alarmProducer, EventLogService eventLog) {
        this.alarmProducer = alarmProducer;
        this.eventLog = eventLog;
    }

    /**
     * Edge-триггер алармов. Аларм публикуется в Kafka ОДИН раз при выходе
     * значения за предел и ещё раз (cleared=true) при возврате в норму.
     * Пока значение остаётся вне предела — повторные алармы НЕ шлются, что
     * убирает «потоп» одинаковых алармов каждую секунду. Гистерезис (deadband)
     * гасит дребезг у самой границы.
     *
     * Severity дифференцируем: ниже min → MINOR (сильно ниже → MAJOR),
     * выше max → MAJOR (сильно выше → CRITICAL).
     */
    public void evaluate(TagEntity tag, double numValue) {
        Double min = tag.getMinValue();
        Double max = tag.getMaxValue();
        if (min == null && max == null) {
            return;
        }

        String unit = tag.getUnit() != null ? tag.getUnit() : "";
        double range = (min != null && max != null)
                ? Math.max(max - min, 0.0001)
                : Math.max(Math.abs(numValue), 1.0);
        double deadband = 0.02 * range; // 2% диапазона — анти-дребезг на возврате в норму

        ActiveAlarm active = activeAlarmByTag.get(tag.getId());

        String condition = null; // HIGH | LOW | null(норма)
        String severity = null;
        double threshold = 0;
        String message = null;

        if (max != null && numValue > max) {
            condition = "HIGH";
            severity = (numValue > max + 0.3 * range) ? "CRITICAL" : "MAJOR";
            threshold = max;
            message = String.format("High value: %.2f > %.2f %s", numValue, max, unit);
        } else if (min != null && numValue < min) {
            condition = "LOW";
            severity = (numValue < min - 0.3 * range) ? "MAJOR" : "MINOR";
            threshold = min;
            message = String.format("Low value: %.2f < %.2f %s", numValue, min, unit);
        }

        if (condition != null) {
            // В зоне аларма: шлём только при НОВОМ эпизоде или смене направления
            // нарушения (LOW↔HIGH); пока то же нарушение — молчим (анти-флуд).
            if (active == null || !active.condition.equals(condition)) {
                if (active != null) {
                    sendClear(tag, active, numValue); // закрываем прежнее нарушение другого знака
                }
                String alarmId = "ALARM_" + tag.getId() + "_" + condition + "_" + System.currentTimeMillis();
                eventLog.logAlarm(tag, severity, message, threshold, numValue);
                alarmProducer.sendAlarm(tag, alarmId, severity, message, threshold, numValue, false);
                activeAlarmByTag.put(tag.getId(), new ActiveAlarm(condition, alarmId, severity, threshold));
            }
            return;
        }

        // Значение в норме: если был активный аларм и вернулись в норму с
        // запасом (deadband) — закрываем эпизод (cleared=true) ровно один раз.
        if (active != null) {
            boolean backToNormal =
                    (min == null || numValue >= min + deadband) &&
                    (max == null || numValue <= max - deadband);
            if (backToNormal) {
                sendClear(tag, active, numValue);
                activeAlarmByTag.remove(tag.getId());
            }
        }
    }

    private void sendClear(TagEntity tag, ActiveAlarm active, double numValue) {
        String message = String.format("Cleared: %.2f back to normal", numValue);
        alarmProducer.sendAlarm(tag, active.alarmId, active.severity, message, active.threshold, numValue, true);
        eventLog.logEvent("ALARM_CLEARED", "OpcUaClient", "INFO",
                String.format("Alarm cleared for %s (%.2f)", tag.getName(), numValue),
                Map.of("tagId", tag.getId(), "tagName", tag.getName(), "alarmId", active.alarmId));
    }

    /** Активный (ещё не закрытый) аларм по тегу: стабильный alarmId на весь эпизод. */
    private static final class ActiveAlarm {
        final String condition;  // LOW | HIGH
        final String alarmId;    // стабильный идентификатор эпизода
        final String severity;
        final double threshold;

        ActiveAlarm(String condition, String alarmId, String severity, double threshold) {
            this.condition = condition;
            this.alarmId = alarmId;
            this.severity = severity;
            this.threshold = threshold;
        }
    }
}
