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
            // Минимальный контракт (аналог OPC UA DataValue): значение + качество +
            // время. value шлём ТИПИЗИРОВАННЫМ (число/bool) — Monitor делает
            // value.asText(), для аналога это должно дать число, иначе график не
            // построится. Адрес тега несёт Kafka-key (= tag.getName() = путь узла);
            // всё статическое (единицы, прибор, контроллер) Monitor берёт из своего
            // конфига по ключу, на проводе его нет.
            TelemetryMessage message = new TelemetryMessage(value, quality, Instant.now());

            CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(telemetryTopic, tag.getName(), message);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    // Успех НЕ пишем в event_log — это был шум (строка в БД на каждую
                    // отправку). Ошибки логируем: они редки и важны.
                    log.debug("✅ Sent telemetry for {} to Kafka", tag.getName());
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

    /**
     * Отправка ОДНОГО ПОЛЯ разобранной записи прибора (режим шлюз-драйвер).
     * channelName = путь канала (id_node) = Kafka-key; тело — тот же триплет.
     */
    public void sendFieldTelemetry(String channelName, Object value, String quality) {
        if (!kafkaEnabled) return;
        try {
            TelemetryMessage message = new TelemetryMessage(value, quality, Instant.now());
            kafkaTemplate.send(telemetryTopic, channelName, message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("❌ Failed to send field {} to Kafka: {}", channelName, ex.getMessage());
                        eventLogService.logKafkaEvent("SEND", telemetryTopic, channelName, "ERROR", ex.getMessage());
                    }
                });
        } catch (Exception e) {
            log.error("❌ Error sending field {}: {}", channelName, e.getMessage());
        }
    }
}
