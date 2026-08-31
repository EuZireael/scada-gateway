package com.scada.gateway.kafka.config;

import com.scada.gateway.kafka.dto.CommandMessage;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Конфигурация Kafka: объявляет топики шлюза (телеметрия / алармы / события /
 * команды) бинами NewTopic — Spring создаёт их при старте, если брокер позволяет.
 */
@Configuration
public class KafkaConfig {

    @Value("${kafka.topics.telemetry:scada-telemetry}")
    private String telemetryTopic;

    @Value("${kafka.topics.alarms:scada-alarms}")
    private String alarmsTopic;

    @Value("${kafka.topics.events:scada-events}")
    private String eventsTopic;

    @Value("${kafka.topics.commands:scada-commands}")
    private String commandsTopic;

    @Value("${kafka.topics.command-results:scada-command-results}")
    private String commandResultsTopic;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:scada-gateway-group}")
    private String groupId;

    @Bean
    public NewTopic telemetryTopic() {
        return TopicBuilder.name(telemetryTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic alarmsTopic() {
        return TopicBuilder.name(alarmsTopic).partitions(2).replicas(1).build();
    }

    @Bean
    public NewTopic eventsTopic() {
        return TopicBuilder.name(eventsTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic commandsTopic() {
        return TopicBuilder.name(commandsTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic commandResultsTopic() {
        return TopicBuilder.name(commandResultsTopic).partitions(1).replicas(1).build();
    }

    // ==================== Консьюмер команд (Monitor → шлюз) ====================

    @Bean
    public ConsumerFactory<String, CommandMessage> commandConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        // Команды управления НЕ переигрываем при рестарте — реагируем только на новые.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

        // Monitor шлёт без тип-заголовков — десериализуем по фиксированному типу.
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, CommandMessage.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.scada.gateway.kafka.dto");

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CommandMessage>
    commandKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CommandMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(commandConsumerFactory());
        return factory;
    }
}