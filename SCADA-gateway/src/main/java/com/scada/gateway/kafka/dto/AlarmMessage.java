package com.scada.gateway.kafka.dto;

import java.time.Instant;

/**
 * DTO для отправки алармов в Kafka
 */
public class AlarmMessage {
    private String messageId;
    private String type = "ALARM";
    private String alarmId;
    private Long tagId;
    private String tagName;
    private String severity;
    private String message;
    private Double threshold;
    private Double currentValue;
    private Instant timestamp;
    private Boolean acknowledged;
    private Boolean cleared;
    private Long controllerId;
    private String controllerName;

    public AlarmMessage() {}

    public AlarmMessage(String messageId, String type, String alarmId, Long tagId, 
                       String tagName, String severity, String message, Double threshold, 
                       Double currentValue, Instant timestamp, Boolean acknowledged, 
                       Boolean cleared, Long controllerId, String controllerName) {
        this.messageId = messageId;
        this.type = type;
        this.alarmId = alarmId;
        this.tagId = tagId;
        this.tagName = tagName;
        this.severity = severity;
        this.message = message;
        this.threshold = threshold;
        this.currentValue = currentValue;
        this.timestamp = timestamp;
        this.acknowledged = acknowledged;
        this.cleared = cleared;
        this.controllerId = controllerId;
        this.controllerName = controllerName;
    }

    // Геттеры и сеттеры
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getAlarmId() { return alarmId; }
    public void setAlarmId(String alarmId) { this.alarmId = alarmId; }

    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }

    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Double getThreshold() { return threshold; }
    public void setThreshold(Double threshold) { this.threshold = threshold; }

    public Double getCurrentValue() { return currentValue; }
    public void setCurrentValue(Double currentValue) { this.currentValue = currentValue; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public Boolean getAcknowledged() { return acknowledged; }
    public void setAcknowledged(Boolean acknowledged) { this.acknowledged = acknowledged; }

    public Boolean getCleared() { return cleared; }
    public void setCleared(Boolean cleared) { this.cleared = cleared; }

    public Long getControllerId() { return controllerId; }
    public void setControllerId(Long controllerId) { this.controllerId = controllerId; }

    public String getControllerName() { return controllerName; }
    public void setControllerName(String controllerName) { this.controllerName = controllerName; }
}