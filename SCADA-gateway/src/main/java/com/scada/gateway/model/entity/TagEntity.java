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
    
    // ========== НОВЫЕ ПОЛЯ ДЛЯ MODBUS ==========
    
    @Column(name = "protocol", nullable = false)
    private String protocol = "opcua";  // "opcua" или "modbus"
    
    @Column(name = "modbus_address")
    private Integer modbusAddress;
    
    @Column(name = "modbus_type")
    private String modbusType;  // "float32", "int16", "int32"
    
    @Column(name = "modbus_unit_id")
    private Integer modbusUnitId = 1;  // Slave ID, по умолчанию 1

    // ========== СВЯЗЬ С ОБЩЕЙ БД КАНАЛОВ ==========
    // channel.node.id соответствующего канала. Уходит в Kafka как tagId,
    // по нему Monitor находит канал (имя/иерархию) в базе каналов.
    @Column(name = "channel_id")
    private Long channelId;

    // Разложение канала на прибор/поле (объектная модель реального ПЛК).
    // Уходит в Kafka metadata → Monitor собирает канал в объект-устройство.
    @Column(name = "device_name")
    private String deviceName;

    @Column(name = "field_name")
    private String fieldName;

    @Column(name = "device_type")
    private String deviceType;

    // Режим сырой записи прибора (шлюз-драйвер парсит ИМЯ={поле=знач,…}).
    @Column(name = "record_device")
    private boolean recordDevice = false;

    // Карта полей записи в JSON: [{"name":"ST","channelId":385,"dataType":"BOOLEAN"},…].
    @Column(name = "fields_json", columnDefinition = "text")
    private String fieldsJson;

    // Доступ к записи — как у реального ПЛК: показание датчика (RO) изменить нельзя,
    // команду актуатора (RW: клапан/мотор/DO) — можно. false (по умолчанию) → шлюз
    // отклоняет команду записи (REJECTED_NOT_WRITABLE) ещё ДО похода в контроллер,
    // для ОБОИХ протоколов. Источник — writable в controllers.yaml.
    @Column(name = "writable")
    private boolean writable = false;

    // ==========================================
    
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
    
    // ========== Геттеры и сеттеры ==========
    
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
    
    // ========== Новые геттеры и сеттеры ==========
    
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    
    public Integer getModbusAddress() { return modbusAddress; }
    public void setModbusAddress(Integer modbusAddress) { this.modbusAddress = modbusAddress; }
    
    public String getModbusType() { return modbusType; }
    public void setModbusType(String modbusType) { this.modbusType = modbusType; }
    
    public Integer getModbusUnitId() { return modbusUnitId; }
    public void setModbusUnitId(Integer modbusUnitId) { this.modbusUnitId = modbusUnitId; }

    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }

    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    public boolean isRecordDevice() { return recordDevice; }
    public void setRecordDevice(boolean recordDevice) { this.recordDevice = recordDevice; }

    public String getFieldsJson() { return fieldsJson; }
    public void setFieldsJson(String fieldsJson) { this.fieldsJson = fieldsJson; }

    public boolean isWritable() { return writable; }
    public void setWritable(boolean writable) { this.writable = writable; }

    // ==========================================
    
    public Object getLastValue() { return lastValue; }
    public void setLastValue(Object lastValue) { this.lastValue = lastValue; }
    
    public Instant getLastReadTime() { return lastReadTime; }
    public void setLastReadTime(Instant lastReadTime) { this.lastReadTime = lastReadTime; }
    
    // ========== Вспомогательные методы ==========
    
    public boolean isModbus() {
        return "modbus".equalsIgnoreCase(protocol);
    }
    
    public boolean isOpcUa() {
        return "opcua".equalsIgnoreCase(protocol);
    }
}