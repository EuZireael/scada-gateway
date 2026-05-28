package com.scada.gateway.kafka.dto;

import java.time.Instant;
import java.util.Map;

/**
 * DTO для отправки системных событий в Kafka
 */
public class EventMessage {
    private String messageId;
    private String type = "EVENT";
    private String eventType;
    private String source;
    private String severity;
    private String message;
    private Instant timestamp;
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