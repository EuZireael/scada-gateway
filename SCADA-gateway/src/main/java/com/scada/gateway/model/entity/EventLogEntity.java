package com.scada.gateway.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "event_log")
public class EventLogEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "event_time", nullable = false)
    private Instant eventTime;
    
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;  // CONNECTION, DISCONNECTION, TAG_READ, ALARM, ERROR, SYSTEM
    
    @Column(name = "source", length = 100)
    private String source;      // OpcUaClient, KafkaProducer, etc.
    
    @Column(name = "severity", length = 20)
    private String severity;    // INFO, WARNING, ERROR, CRITICAL
    
    @Column(name = "message", length = 500)
    private String message;
    
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;      // JSON с дополнительной информацией
    
    @Column(name = "tag_id")
    private Long tagId;          // Связь с тегом (если есть)
    
    @Column(name = "controller_id")
    private Long controllerId;   // Связь с контроллером (если есть)
    
    @Column(name = "user_id")
    private String userId;       // Кто выполнил действие (если есть)
    
    @Column(name = "acknowledged")
    private Boolean acknowledged = false;  // Для алармов
    
    // Конструкторы
    public EventLogEntity() {}
    
    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    
    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }
    
    public Long getControllerId() { return controllerId; }
    public void setControllerId(Long controllerId) { this.controllerId = controllerId; }
    
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public Boolean getAcknowledged() { return acknowledged; }
    public void setAcknowledged(Boolean acknowledged) { this.acknowledged = acknowledged; }
}