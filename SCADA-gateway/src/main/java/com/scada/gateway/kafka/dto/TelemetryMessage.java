package com.scada.gateway.kafka.dto;

import java.time.Instant;
import java.util.Map;

/**
 * DTO для отправки телеметрии в Kafka
 */
public class TelemetryMessage {
    private String messageId;
    private String type = "TELEMETRY";
    private Long tagId;
    private String tagName;
    private Object value;
    private Double numericValue;
    private String stringValue;
    private String unit;
    private String quality;
    private Instant timestamp;
    private Long controllerId;
    private String controllerName;
    private Map<String, Object> metadata;

    public TelemetryMessage() {}

    public TelemetryMessage(String messageId, String type, Long tagId, String tagName, 
                           Object value, Double numericValue, String stringValue, 
                           String unit, String quality, Instant timestamp, 
                           Long controllerId, String controllerName, 
                           Map<String, Object> metadata) {
        this.messageId = messageId;
        this.type = type;
        this.tagId = tagId;
        this.tagName = tagName;
        this.value = value;
        this.numericValue = numericValue;
        this.stringValue = stringValue;
        this.unit = unit;
        this.quality = quality;
        this.timestamp = timestamp;
        this.controllerId = controllerId;
        this.controllerName = controllerName;
        this.metadata = metadata;
    }

    // Геттеры и сеттеры
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }

    public String getTagName() { return tagName; }
    public void setTagName(String tagName) { this.tagName = tagName; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public Double getNumericValue() { return numericValue; }
    public void setNumericValue(Double numericValue) { this.numericValue = numericValue; }

    public String getStringValue() { return stringValue; }
    public void setStringValue(String stringValue) { this.stringValue = stringValue; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public Long getControllerId() { return controllerId; }
    public void setControllerId(Long controllerId) { this.controllerId = controllerId; }

    public String getControllerName() { return controllerName; }
    public void setControllerName(String controllerName) { this.controllerName = controllerName; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}