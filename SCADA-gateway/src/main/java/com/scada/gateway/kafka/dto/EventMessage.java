package com.scada.gateway.kafka.dto;

import java.time.Instant;
import java.util.Map;

/**
 * DTO системного события шлюза для Kafka (топик scada-events): смена связи/качества,
 * старт/стоп, ошибки. Потребитель — Monitor Srv (вкладка событий).
 */
public class EventMessage {
    /** Уникальный id сообщения (UUID) — для дедупликации на приёмнике. */
    private String messageId;
    /** Категория конверта (константа "EVENT") — отличает от алармов/телеметрии. */
    private String type = "EVENT";
    /** Тип события: CONNECTION/HEARTBEAT/SYSTEM/QUALITY_CHANGE и т.п. */
    private String eventType;
    /** Компонент-источник (OpcUaClient, Gateway, …). */
    private String source;
    /** Важность: INFO/WARNING/ERROR/CRITICAL. */
    private String severity;
    /** Человекочитаемый текст события. */
    private String message;
    /** Момент события. */
    private Instant timestamp;
    /** Произвольные детали (контроллер, endpoint, причина) — свободная карта. */
    private Map<String, Object> details;

    public EventMessage() {}

    public EventMessage(String messageId, String type, String eventType, String source, 
                       String severity, String message, Instant timestamp, Map<String, Object> details) {
        this.messageId = messageId;
        this.type = type;
        this.eventType = eventType;
        this.source = source;
        this.severity = severity;
        this.message = message;
        this.timestamp = timestamp;
        this.details = details;
    }

    // Геттеры и сеттеры
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
}