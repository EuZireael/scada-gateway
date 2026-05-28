package com.scada.gateway.kafka.producer;

import com.scada.gateway.kafka.dto.TelemetryMessage;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.service.EventLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Service
public class TelemetryProducer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String telemetryTopic;
    private final EventLogService eventLogService;
    
    @Value("${kafka.enabled:false}")
    private boolean kafkaEnabled;

    public TelemetryProducer(KafkaTemplate<String, Object> kafkaTemplate,
                             @Value("${kafka.topics.telemetry}") String telemetryTopic,
                             EventLogService eventLogService) {
        this.kafkaTemplate = kafkaTemplate;
        this.telemetryTopic = telemetryTopic;
        this.eventLogService = eventLogService;
    }

    public void sendTelemetry(TagEntity tag, Object value, String quality) {
        if (!kafkaEnabled) {
            log.debug("Kafka disabled, skipping send for tag: {}", tag.getName());
            return;
        }

        try {
            TelemetryMessage message = new TelemetryMessage();
            message.setTagId(tag.getId());
            message.setTagName(tag.getName());
            message.setValue(value != null ? value.toString() : null);
            message.setQuality(quality);
            message.setTimestamp(Instant.now());
            message.setUnit(tag.getUnit());
            message.setControllerId(tag.getController() != null ? tag.getController().getId() : null);

            CompletableFuture<SendResult<String, Object>> future = 
                kafkaTemplate.send(telemetryTopic, tag.getName(), message);
            
            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.debug("✅ Sent telemetry for {} to Kafka", tag.getName());
                    eventLogService.logKafkaEvent("SEND", telemetryTopic, tag.getName(), "SUCCESS", null);
                } else {
                    log.error("❌ Failed to send telemetry for {} to Kafka: {}", tag.getName(), ex.getMessage());
                    eventLogService.logKafkaEvent("SEND", telemetryTopic, tag.getName(), "ERROR", ex.getMessage());
                }
            });
            
        } catch (Exception e) {
            log.error("❌ Error sending telemetry for {}: {}", tag.getName(), e.getMessage());
            eventLogService.logError("KafkaProducer", "Failed to send telemetry for tag " + tag.getName(), e, tag, null);
        }
    }
}