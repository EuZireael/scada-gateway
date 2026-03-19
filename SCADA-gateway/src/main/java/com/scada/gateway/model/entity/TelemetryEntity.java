package com.scada.gateway.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "telemetry")
public class TelemetryEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "tag_id", nullable = false)
    private Long tagId;
    
    @Column(name = "time", nullable = false)
    private Instant time;
    
    @Column(name = "value")
    private Double value;
    
    @Column(name = "value_str")
    private String valueString;
    
    @Column(name = "quality", length = 20)
    private String quality;
    
    @Column(name = "raw_data")
    private byte[] rawData;
    
    // Пустой конструктор обязателен для JPA
    public TelemetryEntity() {}
    
    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }
    
    public Instant getTime() { return time; }
    public void setTime(Instant time) { this.time = time; }
    
    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }
    
    public String getValueString() { return valueString; }
    public void setValueString(String valueString) { this.valueString = valueString; }
    
    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }
    
    public byte[] getRawData() { return rawData; }
    public void setRawData(byte[] rawData) { this.rawData = rawData; }
}