package com.scada.gateway.model;

import java.time.Instant;

/**
 * Модель данных для передачи значения тега (без Lombok).
 */
public class TagValue {
    private String serverId;
    private String tagId;
    private String tagName;
    private Object value;
    private String dataType;
    private String quality;
    private Instant timestamp;
    private String unit;

    // --- Конструкторы ---
    public TagValue() {}

    public TagValue(String serverId, String tagId, String tagName, Object value,
                    String dataType, String quality, Instant timestamp, String unit) {
        this.serverId = serverId;
        this.tagId = tagId;
        this.tagName = tagName;
        this.value = value;
        this.dataType = dataType;
        this.quality = quality;
        this.timestamp = timestamp;
        this.unit = unit;
    }

    // --- Геттеры и Сеттеры ---
    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getTagId() {
        return tagId;
    }

    public void setTagId(String tagId) {
        this.tagId = tagId;
    }

    public String getTagName() {
        return tagName;
    }

    public void setTagName(String tagName) {
        this.tagName = tagName;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getQuality() {
        return quality;
    }

    public void setQuality(String quality) {
        this.quality = quality;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }
}