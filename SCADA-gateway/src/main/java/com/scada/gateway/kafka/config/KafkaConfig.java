package com.scada.gateway.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${kafka.topics.telemetry:scada-telemetry}")
    private String telemetryTopic;

    @Value("${kafka.topics.alarms:scada-alarms}")
    private String alarmsTopic;

    @Value("${kafka.topics.events:scada-events}")
    private String eventsTopic;

    @Bean
    public NewTopic telemetryTopic() {
        return TopicBuilder.name(telemetryTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic alarmsTopic() {
        return TopicBuilder.name(alarmsTopic)
                .partitions(2)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic eventsTopic() {
        return TopicBuilder.name(eventsTopic)
                .partitions(1)
                .replicas(1)
                .build();
    }
}