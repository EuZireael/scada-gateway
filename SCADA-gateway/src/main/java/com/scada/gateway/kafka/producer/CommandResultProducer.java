package com.scada.gateway.kafka.producer;

import com.scada.gateway.kafka.dto.CommandResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Публикует результат выполнения команды в Kafka-топик scada-command-results.
 * Потребитель — Monitor Srv, который доставляет результат в UI по WebSocket.
 */
@Service
public class CommandResultProducer {

    private static final Logger log = LoggerFactory.getLogger(CommandResultProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String resultsTopic;

    @Value("${kafka.enabled:false}")
    private boolean kafkaEnabled;

    public CommandResultProducer(KafkaTemplate<String, Object> kafkaTemplate,
                                 @Value("${kafka.topics.command-results:scada-command-results}") String resultsTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.resultsTopic = resultsTopic;
    }

    public void send(CommandResultMessage result) {
        if (!kafkaEnabled) {
            log.debug("Kafka disabled, skipping command result for: {}", result.getTagName());
            return;
        }
        kafkaTemplate.send(resultsTopic, result.getCommandId(), result);
        log.info("→ результат команды {}: {} ({})",
                result.getCommandId(), result.getStatus(), result.getMessage());
    }
}
