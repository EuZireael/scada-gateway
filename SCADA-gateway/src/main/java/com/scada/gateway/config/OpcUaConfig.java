package com.scada.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "opcua")
public class OpcUaConfig {
    
    private List<OpcUaServerConfig> servers;
    
    public List<OpcUaServerConfig> getServers() { return servers; }
    public void setServers(List<OpcUaServerConfig> servers) { this.servers = servers; }
    
    public static class OpcUaServerConfig {
        private String id;
        private String name;
        private String endpoint;
        private String security;
        private String username;
        private String password;
        private boolean enabled;
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
    
    public static class TagConfig {
        private String nodeId;
        private String name;
        private String dataType;
        private long pollingRate;
        private boolean enabled;
        private String unit;
        
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
    }
}