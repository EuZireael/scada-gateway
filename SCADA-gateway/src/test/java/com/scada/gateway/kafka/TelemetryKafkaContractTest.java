package com.scada.gateway.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scada.gateway.kafka.producer.TelemetryProducer;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.service.EventLogService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * КОНТРАКТНЫЙ тест телеметрии с ВСТРОЕННЫМ Kafka-брокером (@EmbeddedKafka, в JVM, без Docker).
 *
 * <p>Дополняет {@link TelemetryContractTest} (чистый unit) двумя вещами, которые unit покрыть
 * не мог:
 *   1) РЕАЛЬНЫЙ топик из application.yaml — тест грузит настоящий конфиг и проверяет, что
 *      {@code kafka.topics.telemetry == scada.tags} (тот, что слушает монитор). Сменят имя в
 *      конфиге — тест покраснеет;
 *   2) СКВОЗНОЙ round-trip — реальная сериализация (spring-kafka JsonSerializer) → брокер →
 *      консьюмер читает СЫРЫЕ БАЙТЫ провода и сверяет ключ + JSON-тело.
 *
 * <p>Контекст поднимается минимальный (@SpringBootConfiguration только с KafkaTemplate и
 * продюсером) — БЕЗ БД/OPC UA/полного приложения, поэтому тест быстрый и не требует инфраструктуры.
 */
@SpringBootTest(
        classes = TelemetryKafkaContractTest.KafkaTestConfig.class,
        properties = "kafka.enabled=true")
@EmbeddedKafka(partitions = 1, topics = {"scada.tags"})
class TelemetryKafkaContractTest {

    @Autowired TelemetryProducer producer;
    @Autowired EmbeddedKafkaBroker broker;

    /** Реальное значение из application.yaml — сменят топик в конфиге, тест это увидит. */
    @Value("${kafka.topics.telemetry}") String telemetryTopic;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Топик телеметрии в application.yaml = scada.tags (его слушает монитор)")
    void configuredTopicIsScadaTags() {
        assertEquals("scada.tags", telemetryTopic,
                "монитор слушает scada.tags — имя топика меняем только согласованно с монитором");
    }

    @Test
    @DisplayName("Round-trip: запись приходит в scada.tags с ключом=путь канала и телом {value,quality,timestamp}")
    void endToEndWireContract() throws Exception {
        String channel = "Барановичи-1.BN1_MCA1.V_M_1.LINE1V0.M";
        Instant ts = Instant.now();

        // Консьюмер поднимаем ДО отправки (offset=earliest), чтобы гарантированно поймать запись.
        Map<String, Object> cprops = KafkaTestUtils.consumerProps("contract-test", "true", broker);
        cprops.put("key.deserializer", StringDeserializer.class);
        cprops.put("value.deserializer", StringDeserializer.class);   // тело читаем как СЫРОЙ JSON
        try (Consumer<String, String> consumer = new KafkaConsumer<>(cprops)) {
            broker.consumeFromEmbeddedTopics(consumer, "scada.tags");

            TagEntity tag = new TagEntity();
            tag.setName(channel);
            producer.sendTelemetry(tag, 1.07, "GOOD", ts);

            ConsumerRecord<String, String> rec =
                    KafkaTestUtils.getSingleRecord(consumer, "scada.tags", Duration.ofSeconds(15));

            // Ключ = путь канала: по нему монитор маршрутизирует значение.
            assertEquals(channel, rec.key(), "Kafka-key должен быть путём канала");

            // Тело — сырые байты провода: ровно {value, quality, timestamp}, value типизирован.
            JsonNode body = mapper.readTree(rec.value());
            assertTrue(body.get("value").isNumber(), "value должен уйти числом, не строкой");
            assertEquals(1.07, body.get("value").asDouble(), 1e-9);
            assertEquals("GOOD", body.get("quality").asText());
            assertTrue(body.hasNonNull("timestamp"), "нет timestamp");
        }
    }

    /**
     * Минимальный контекст: только KafkaTemplate (JsonSerializer, нацелен на встроенный брокер)
     * и сам TelemetryProducer. Полное приложение (БД, OPC UA, @Scheduled) НЕ поднимается.
     */
    @SpringBootConfiguration
    static class KafkaTestConfig {

        /** Резолвер ${...} для @Value (в минимальном контексте без автоконфигурации). */
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        KafkaTemplate<String, Object> kafkaTemplate(EmbeddedKafkaBroker broker) {
            Map<String, Object> props = KafkaTestUtils.producerProps(broker);
            // producerProps по умолчанию ставит IntegerSerializer для ключа — а ключ у нас
            // строковый (путь канала). Ставим как в проде: ключ — строка, тело — JSON.
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }

        @Bean
        TelemetryProducer telemetryProducer(KafkaTemplate<String, Object> template,
                                            @Value("${kafka.topics.telemetry}") String topic) {
            // EventLogService в этом тесте не нужен по сути — мок.
            return new TelemetryProducer(template, topic, mock(EventLogService.class));
        }
    }
}
