package com.scada.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Биндинг конфигурации контроллеров и тегов из controllers.yaml (префикс "opcua").
 * Вложенные классы описывают сервер (OPC UA/Modbus endpoint) и его теги; из этой
 * структуры ConfigurationService засевает БД при старте.
 */
@Component
@ConfigurationProperties(prefix = "opcua")
public class OpcUaConfig {
    
    private List<OpcUaServerConfig> servers;
    
    public List<OpcUaServerConfig> getServers() { return servers; }
    public void setServers(List<OpcUaServerConfig> servers) { this.servers = servers; }
    
    /** Один контроллер из YAML (OPC UA или Modbus) со своим списком тегов. */
    public static class OpcUaServerConfig {
        /** Идентификатор из YAML (опционально; в БД ключ — name). */
        private String id;
        /** Уникальное имя контроллера — ключ upsert в БД. */
        private String name;
        /** Адрес: opc.tcp://… или modbus://host:port. */
        private String endpoint;
        /** Политика безопасности OPC UA (сейчас NONE). */
        private String security;
        /** Учётка OPC UA (если требуется). */
        private String username;
        private String password;
        /** Опрашивать ли контроллер. */
        private boolean enabled;
        /** Теги контроллера. */
        private List<TagConfig> tags;
        
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        
        public String getSecurity() { return security; }
        public void setSecurity(String security) { this.security = security; }
        
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public List<TagConfig> getTags() { return tags; }
        public void setTags(List<TagConfig> tags) { this.tags = tags; }
    }
    
    /** Один тег/канал из YAML: адресация, тип, пороги и (ниже) параметры прибора/Modbus. */
    public static class TagConfig {
        /** Адрес OPC UA-узла и одновременно имя канала. */
        private String nodeId;
        /** Имя тега (путь канала). */
        private String name;
        /** Тип данных (BOOLEAN/INT/FLOAT/…). */
        private String dataType;
        /** Период опроса, мс. */
        private long pollingRate;
        /** Опрашивать ли тег. */
        private boolean enabled;
        /** Единица измерения. */
        private String unit;
        /** Нижний порог аларма (если алармы считает шлюз). */
        private Double minValue;
        /** Верхний порог аларма. */
        private Double maxValue;

        // Идентификатор канала в общей БД каналов (channel.node.id). Именно он
        // уходит в Kafka как tagId, чтобы Monitor нашёл канал в базе.
        private Long channelId;

        // Разложение канала на прибор/поле (как в реальном ПЛК: устройство={поля}).
        // Уходит в Kafka metadata, чтобы Monitor собрал канал в объект-устройство.
        private String deviceName;   // напр. "LINE1V0"
        private String fieldName;    // напр. "ST"
        private String deviceType;   // напр. "V" (клапан)

        // РЕЖИМ ЗАПИСИ УСТРОЙСТВА (как реальный драйвер): этот тег — не один сигнал,
        // а СЫРАЯ запись прибора ИМЯ={поле=знач,…} (OPC UA string). Шлюз парсит её и
        // раскладывает на каналы по карте fields[поле→channelId].
        private boolean recordDevice = false;
        private java.util.List<FieldConfig> fields;

        // Протокол и параметры Modbus (для контроллеров с endpoint modbus://…).
        private String protocol = "opcua";      // "opcua" | "modbus"
        private Integer modbusAddress;           // адрес holding-регистра (напр. 40001)
        private String modbusType;               // "float32" | "int16" | "int32"
        private Integer modbusUnitId = 1;        // Modbus slave id

        // Доступ к записи точки: false (по умолчанию) = только чтение (датчик), true =
        // актуатор (клапан/мотор/DO), команду записи шлюз принимает. См. writeTag.
        private boolean writable = false;

        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDataType() { return dataType; }
        public void setDataType(String dataType) { this.dataType = dataType; }

        public long getPollingRate() { return pollingRate; }
        public void setPollingRate(long pollingRate) { this.pollingRate = pollingRate; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }

        public Double getMinValue() { return minValue; }
        public void setMinValue(Double minValue) { this.minValue = minValue; }

        public Double getMaxValue() { return maxValue; }
        public void setMaxValue(Double maxValue) { this.maxValue = maxValue; }

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

        public java.util.List<FieldConfig> getFields() { return fields; }
        public void setFields(java.util.List<FieldConfig> fields) { this.fields = fields; }

        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }

        public Integer getModbusAddress() { return modbusAddress; }
        public void setModbusAddress(Integer modbusAddress) { this.modbusAddress = modbusAddress; }

        public String getModbusType() { return modbusType; }
        public void setModbusType(String modbusType) { this.modbusType = modbusType; }

        public Integer getModbusUnitId() { return modbusUnitId; }
        public void setModbusUnitId(Integer modbusUnitId) { this.modbusUnitId = modbusUnitId; }

        public boolean isWritable() { return writable; }
        public void setWritable(boolean writable) { this.writable = writable; }
    }

    /** Одно поле прибора внутри записи ИМЯ={поле=знач,…}: имя → канал базы + тип. */
    public static class FieldConfig {
        private String name;       // "ST", "M", "V", "P_MIN_V"…
        private Long channelId;    // channel.node.id → tagId в Kafka
        private String dataType;   // "BOOLEAN" | "FLOAT"

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public Long getChannelId() { return channelId; }
        public void setChannelId(Long channelId) { this.channelId = channelId; }

        public String getDataType() { return dataType; }
        public void setDataType(String dataType) { this.dataType = dataType; }
    }
}