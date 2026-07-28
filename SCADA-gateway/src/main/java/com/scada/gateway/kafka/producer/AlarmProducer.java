package com.scada.gateway.kafka.producer;

import com.scada.gateway.kafka.dto.AlarmMessage;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.service.EventLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class AlarmProducer {

    private static final Logger log = LoggerFactory.getLogger(AlarmProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String alarmTopic;
    private final EventLogService eventLogService;

    @Value("${kafka.enabled:false}")
    private boolean kafkaEnabled;

    // Публикация алармов в Kafka. По умолчанию ВЫКЛ: в проводе — только телеметрия.
    @Value("${kafka.publish.alarms:false}")
    private boolean publishAlarms;

    public AlarmProducer(KafkaTemplate<String, Object> kafkaTemplate,
                         @Value("${kafka.topics.alarms}") String alarmTopic,
                         EventLogService eventLogService) {
        this.kafkaTemplate = kafkaTemplate;
        this.alarmTopic = alarmTopic;
        this.eventLogService = eventLogService;
    }

    public void sendAlarm(TagEntity tag, String alarmId, String severity, String message,
                          Double threshold, Double currentValue, boolean cleared) {
        if (!kafkaEnabled || !publishAlarms) {
            return;
        }

        try {
            AlarmMessage alarm = new AlarmMessage();
            alarm.setMessageId(UUID.randomUUID().toString());
            // alarmId стабилен на весь эпизод (raise→clear), поэтому Monitor
            // дедуплицирует повторы и корректно закрывает аларм по cleared.
            alarm.setAlarmId(alarmId);
            // tagId = id канала в общей БД каналов (см. TelemetryProducer).
            alarm.setTagId(tag.getChannelId() != null ? tag.getChannelId() : tag.getId());
            alarm.setTagName(tag.getName());
            alarm.setSeverity(severity);
            alarm.setMessage(message);
            alarm.setThreshold(threshold);
            alarm.setCurrentValue(currentValue);
            alarm.setTimestamp(Instant.now());
            alarm.setAcknowledged(false);
            alarm.setCleared(cleared);

            // getId() безопасен на LAZY-прокси контроллера (FK уже известен).
            // getName() НЕ трогаем — он триггерит инициализацию и кидает
            // LazyInitializationException на detached-сущности.
            alarm.setControllerId(tag.getController() != null ? tag.getController().getId() : null);

            CompletableFuture.supplyAsync(() -> kafkaTemplate.send(alarmTopic, tag.getName(), alarm))
                    .thenAccept(result -> {
                        log.info("🚨 Alarm sent to Kafka: {} = {} [{}]", tag.getName(), currentValue, severity);
                        eventLogService.logKafkaEvent("ALARM_SEND", alarmTopic, tag.getName(), "SUCCESS", severity);
                    })
                    .exceptionally(ex -> {
                        log.error("Failed to send alarm to Kafka: {}", ex.getMessage());
                        eventLogService.logKafkaEvent("ALARM_SEND", alarmTopic, tag.getName(), "ERROR", ex.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            log.error("Error sending alarm: {}", e.getMessage());
            eventLogService.logError("AlarmProducer", "Failed to send alarm for " + tag.getName(), e, tag, null);
        }
    }
}
