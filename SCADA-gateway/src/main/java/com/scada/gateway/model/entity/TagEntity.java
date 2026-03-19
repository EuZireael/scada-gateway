package com.scada.gateway.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "tags")
public class TagEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "controller_id", nullable = false)
    private ControllerEntity controller;
    
    @Column(name = "node_id", nullable = false)
    private String nodeId;
    
    @Column(nullable = false)
    private String name;
    
    @Column(name = "description")
    private String description;
    
    @Column(name = "data_type", nullable = false)
    private String dataType;
    
    @Column(name = "unit")
    private String unit;
    
    @Column(name = "polling_rate")
    private Long pollingRate = 1000L;
    
    @Column(name = "enabled")
    private boolean enabled = true;
    
    @Column(name = "min_value")
    private Double minValue;
    
    @Column(name = "max_value")
    private Double maxValue;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Transient
    private Object lastValue;
    
    @Transient
    private Instant lastReadTime;
    
    // Пустой конструктор обязателен для JPA
    public TagEntity() {}
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public ControllerEntity getController() { return controller; }
    public void setController(ControllerEntity controller) { this.controller = controller; }
    
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    
    public Long getPollingRate() { return pollingRate; }
    public void setPollingRate(Long pollingRate) { this.pollingRate = pollingRate; }
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    
    public Double getMinValue() { return minValue; }
    public void setMinValue(Double minValue) { this.minValue = minValue; }
    
    public Double getMaxValue() { return maxValue; }
    public void setMaxValue(Double maxValue) { this.maxValue = maxValue; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    
    public Object getLastValue() { return lastValue; }
    public void setLastValue(Object lastValue) { this.lastValue = lastValue; }
    
    public Instant getLastReadTime() { return lastReadTime; }
    public void setLastReadTime(Instant lastReadTime) { this.lastReadTime = lastReadTime; }
}