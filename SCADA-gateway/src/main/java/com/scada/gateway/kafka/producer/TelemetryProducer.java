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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TelemetryProducer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryProducer.class);

    // Дешёвый messageId: монотонный счётчик вместо UUID.randomUUID() (SecureRandom,
    // синхронизирован — узкое место на потоке 2471 сообщение/сек). Сид от времени
    // старта → устойчив к коллизиям между перезапусками процесса.
    private static final AtomicLong MSG_SEQ = new AtomicLong(System.currentTimeMillis() * 1000);

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
            message.setMessageId(Long.toString(MSG_SEQ.incrementAndGet()));
            message.setType("TELEMETRY");
            // tagId = id канала в общей БД каналов (channel.node.id), а не локальный
            // PK шлюза: по нему Monitor резолвит канал. Фолбэк на PK, если не задан.
            message.setTagId(tag.getChannelId() != null ? tag.getChannelId() : tag.getId());
            // tagName = полный путь канала (id_node), напр. "Барановичи-1.MCA1.LS_ST.LS4".
            message.setTagName(tag.getName());
            // Шлём ТИПИЗИРОВАННОЕ значение (число/булево), а не value.toString():
            // иначе на стороне Monitor value приходит строкой, numericValue=null,
            // и график не строится. Дополнительно заполняем numericValue/stringValue.
            message.setValue(value);
            message.setNumericValue(toNumeric(value));
            message.setStringValue(value != null ? value.toString() : null);
            message.setQuality(quality);
            message.setTimestamp(Instant.now());
            message.setUnit(tag.getUnit());
            // Только getId() безопасен на LAZY-прокси контроллера; getName() трогать нельзя
            // (LazyInitializationException на detached-сущности).
            message.setControllerId(tag.getController() != null ? tag.getController().getId() : null);
            // Объектная модель реального ПЛК: канал = <прибор>.<поле>. Кладём в metadata,
            // чтобы Monitor собрал каналы одного прибора в единый объект-устройство.
            if (tag.getDeviceName() != null) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("device", tag.getDeviceName());
                meta.put("field", tag.getFieldName());
                meta.put("deviceType", tag.getDeviceType());
                message.setMetadata(meta);
            }

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
     * tagId = channelId поля (channel.node.id). Прибор/поле кладём в metadata,
     * чтобы Monitor собрал запись обратно в объект-устройство.
     */
    public void sendFieldTelemetry(Long channelId, String channelName, Object value, String quality,
                                   String device, String field, String deviceType, Long controllerId) {
        if (!kafkaEnabled) return;
        try {
            TelemetryMessage message = new TelemetryMessage();
            message.setMessageId(Long.toString(MSG_SEQ.incrementAndGet()));
            message.setType("TELEMETRY");
            message.setTagId(channelId);
            message.setTagName(channelName);
            message.setValue(value);
            message.setNumericValue(toNumeric(value));
            message.setStringValue(value != null ? value.toString() : null);
            message.setQuality(quality);
            message.setTimestamp(Instant.now());
            message.setControllerId(controllerId);
            Map<String, Object> meta = new HashMap<>();
            meta.put("device", device);
            meta.put("field", field);
            meta.put("deviceType", deviceType);
            message.setMetadata(meta);

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

    /** Числовое представление значения для графика (число, булево → 0/1, числовая строка). */
    private Double toNumeric(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof Boolean) return ((Boolean) value) ? 1.0 : 0.0;
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}