package com.scada.gateway.kafka.producer;

import com.scada.gateway.kafka.dto.EventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Публикует системные события шлюза в Kafka-топик scada-events.
 *
 * Раньше события только писались в БД шлюза (EventLogService), а в Kafka
 * не уходили — топик scada-events был пустым, и Monitor Srv не получал
 * ни одного события. Этот продюсер закрывает пробел.
 */
@Service
public class EventProducer {

    private static final Logger log = LoggerFactory.getLogger(EventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String eventsTopic;

    @Value("${kafka.enabled:false}")
    private boolean kafkaEnabled;

    // Публикация событий в Kafka. По умолчанию ВЫКЛ: в проводе — только телеметрия
    // (значения). События по-прежнему пишутся в БД шлюза через EventLogService.
    @Value("${kafka.publish.events:false}")
    private boolean publishEvents;

    public EventProducer(KafkaTemplate<String, Object> kafkaTemplate,
                         @Value("${kafka.topics.events}") String eventsTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.eventsTopic = eventsTopic;
    }

    public void sendEvent(String eventType, String source, String severity,
                          String message, Map<String, Object> details) {
        if (!kafkaEnabled || !publishEvents) {
            return;
        }

        try {
            EventMessage event = new EventMessage();
            event.setMessageId(UUID.randomUUID().toString());
            event.setType("EVENT");
            event.setEventType(eventType);
            event.setSource(source);
            event.setSeverity(severity);
            event.setMessage(message);
            event.setTimestamp(Instant.now());
            event.setDetails(details != null ? details : new HashMap<>());

            CompletableFuture<?> future = kafkaTemplate.send(eventsTopic, eventType, event);
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.debug("📨 Event sent to Kafka: type={}, severity={}", eventType, severity);
                } else {
                    log.error("❌ Failed to send event {} to Kafka: {}", eventType, ex.getMessage());
                }
            });

        } catch (Exception e) {
            log.error("❌ Error sending event {}: {}", eventType, e.getMessage());
        }
    }
}
