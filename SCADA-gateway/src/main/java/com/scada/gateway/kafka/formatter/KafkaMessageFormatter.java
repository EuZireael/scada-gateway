package com.scada.gateway.kafka.formatter;

import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.kafka.dto.AlarmMessage;
import com.scada.gateway.kafka.dto.EventMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class KafkaMessageFormatter {

    /**
     * Форматирует аларм для отправки в Kafka
     */
    public AlarmMessage formatAlarm(String alarmId, TagEntity tag, String severity,
                                   String message, Double threshold, Double currentValue) {
        AlarmMessage alarm = new AlarmMessage();

        alarm.setMessageId(UUID.randomUUID().toString());
        alarm.setType("ALARM");
        alarm.setAlarmId(alarmId);
        alarm.setTagId(tag.getId());
        alarm.setTagName(tag.getName());
        alarm.setSeverity(severity);
        alarm.setMessage(message);
        alarm.setThreshold(threshold);
        alarm.setCurrentValue(currentValue);
        alarm.setTimestamp(Instant.now());
        alarm.setAcknowledged(false);
        alarm.setCleared(false);

        if (tag.getController() != null) {
            alarm.setControllerId(tag.getController().getId());
            alarm.setControllerName(tag.getController().getName());
        }

        return alarm;
    }

    /**
     * Форматирует системное событие для отправки в Kafka
     */
    public EventMessage formatEvent(String eventType, String source, String severity,
                                   String message, Map<String, Object> details) {
        EventMessage event = new EventMessage();

        event.setMessageId(UUID.randomUUID().toString());
        event.setType("EVENT");
        event.setEventType(eventType);
        event.setSource(source);
        event.setSeverity(severity);
        event.setMessage(message);
        event.setTimestamp(Instant.now());
        event.setDetails(details != null ? details : new HashMap<>());

        return event;
    }
}
