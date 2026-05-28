package com.scada.gateway.opcua;

import com.scada.gateway.model.entity.ControllerEntity;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.model.entity.TelemetryEntity;
import com.scada.gateway.service.ConfigurationService;
import com.scada.gateway.repository.TelemetryRepository;
import com.scada.gateway.kafka.producer.TelemetryProducer;
import com.scada.gateway.modbus.ModbusClientService;
import com.scada.gateway.service.EventLogService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.transaction.Transactional;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class OpcUaClientServiceDB {

    private static final Logger log = LoggerFactory.getLogger(OpcUaClientServiceDB.class);

    private final ConfigurationService configurationService;
    private final TelemetryRepository telemetryRepository;
    private final TelemetryProducer telemetryProducer;
    private final ModbusClientService modbusClientService;
    private final EventLogService eventLogService;

    private final Map<Long, OpcUaClient> opcClients = new ConcurrentHashMap<>();
    private final Map<Long, ExecutorService> executors = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> runningStatus = new ConcurrentHashMap<>();

    private final Map<Long, TagEntity> tagCache = new ConcurrentHashMap<>();

    public OpcUaClientServiceDB(ConfigurationService configurationService,
                                TelemetryRepository telemetryRepository,
                                TelemetryProducer telemetryProducer,
                                ModbusClientService modbusClientService,
                                EventLogService eventLogService) {
        this.configurationService = configurationService;
        this.telemetryRepository = telemetryRepository;
        this.telemetryProducer = telemetryProducer;
        this.modbusClientService = modbusClientService;
        this.eventLogService = eventLogService;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing Unified Protocol Client Service");
        eventLogService.logSystem("INFO", "SCADA Gateway starting up", Map.of("component", "OpcUaClientService"));

        loadConfiguration();

        List<ControllerEntity> controllers = configurationService.getAllControllers();

        for (ControllerEntity controller : controllers) {
            if (controller.isEnabled()) {
                connectToController(controller);
            }
        }
    }

    private void loadConfiguration() {
        var tags = configurationService.getAllActiveTags();
        tags.forEach(tag -> tagCache.put(tag.getId(), tag));
        log.info("Loaded {} tags", tagCache.size());
        eventLogService.logSystem("INFO", "Configuration loaded", Map.of("tags", tagCache.size(), "controllers", configurationService.getAllControllers().size()));
    }

    private void connectToController(ControllerEntity controller) {
        String endpoint = controller.getEndpoint();
        
        if (endpoint != null && endpoint.toLowerCase().contains("modbus")) {
            log.info("📡 Modbus controller: {} at {}", controller.getName(), endpoint);
            startPollingForController(controller);
        } else if (endpoint != null && endpoint.toLowerCase().contains("opc.tcp")) {
            connectOpcUaController(controller);
        } else {
            log.warn("Unknown protocol for controller: {}", controller.getName());
            eventLogService.logSystem("WARNING", "Unknown protocol for controller: " + controller.getName(), Map.of("controller", controller.getName(), "endpoint", endpoint));
        }
    }

    private void connectOpcUaController(ControllerEntity controller) {
        try {
            log.info("🔌 Connecting OPC UA: {} at {}", controller.getName(), controller.getEndpoint());
            eventLogService.logConnection(controller, "CONNECTING", null);

            List<EndpointDescription> endpoints =
                    DiscoveryClient.getEndpoints(controller.getEndpoint()).get();

            if (endpoints.isEmpty()) {
                log.error("No endpoints found for {}", controller.getEndpoint());
                eventLogService.logConnection(controller, "ERROR", "No endpoints found");
                return;
            }

            EndpointDescription endpoint = endpoints.get(0);

            OpcUaClientConfig config = OpcUaClientConfig.builder()
                    .setApplicationName(LocalizedText.english("SCADA Gateway"))
                    .setApplicationUri("urn:scada:gateway")
                    .setEndpoint(endpoint)
                    .build();

            OpcUaClient client = OpcUaClient.create(config);
            client.connect().get();

            opcClients.put(controller.getId(), client);
            runningStatus.put(controller.getId(), true);

            startPollingForController(controller);

            log.info("✅ OPC UA connected: {}", controller.getName());
            eventLogService.logConnection(controller, "CONNECTED", null);

        } catch (Exception e) {
            log.error("❌ OPC UA connection failed for {}: {}", controller.getName(), e.getMessage());
            eventLogService.logConnection(controller, "ERROR", e.getMessage());
            eventLogService.logError("OpcUaClient", "Failed to connect to " + controller.getName(), e, null, controller);
        }
    }

    private void startPollingForController(ControllerEntity controller) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.put(controller.getId(), executor);
        runningStatus.put(controller.getId(), true);

        String endpoint = controller.getEndpoint();
        boolean isModbus = endpoint != null && endpoint.toLowerCase().contains("modbus");

        if (isModbus) {
            startModbusPolling(controller, executor);
        } else {
            startOpcuaPolling(controller, executor);
        }
    }

    private void startOpcuaPolling(ControllerEntity controller, ExecutorService executor) {
        executor.submit(() -> {
            OpcUaClient client = opcClients.get(controller.getId());
            
            while (runningStatus.getOrDefault(controller.getId(), false)) {
                try {
                    List<TagEntity> tags = configurationService.getTagsForController(controller.getId());

                    for (TagEntity tag : tags) {
                        if (!tag.isEnabled() || !isOpcUaTag(tag)) continue;

                        try {
                            NodeId nodeId = NodeId.parse(tag.getNodeId());
                            DataValue dataValue = client.readValue(0, TimestampsToReturn.Both, nodeId).get();
                            Object val = extractValue(dataValue.getValue());
                            String quality = dataValue.getStatusCode().isGood() ? "GOOD" : "BAD";
                            
                            processTagValue(tag, val, quality);
                            
                        } catch (Exception e) {
                            log.error("OPC UA read error for tag {}: {}", tag.getName(), e.getMessage());
                            eventLogService.logError("OpcUaClient", "Failed to read tag " + tag.getName(), e, tag, controller);
                            processTagValue(tag, null, "BAD");
                        }

                        Thread.sleep(tag.getPollingRate());
                    }

                } catch (InterruptedException e) {
                    log.info("Polling interrupted for {}", controller.getName());
                    break;
                } catch (Exception e) {
                    log.error("Polling error for {}: {}", controller.getName(), e.getMessage());
                    eventLogService.logError("OpcUaClient", "Polling error for " + controller.getName(), e, null, controller);
                }
            }
        });
    }

    private void startModbusPolling(ControllerEntity controller, ExecutorService executor) {
        String host = extractModbusHost(controller.getEndpoint());
        int port = extractModbusPort(controller.getEndpoint(), 502);

        eventLogService.logConnection(controller, "CONNECTING", "Modbus endpoint: " + controller.getEndpoint());

        executor.submit(() -> {
            while (runningStatus.getOrDefault(controller.getId(), false)) {
                try {
                    List<TagEntity> tags = configurationService.getTagsForController(controller.getId());

                    for (TagEntity tag : tags) {
                        if (!tag.isEnabled() || !isModbusTag(tag)) continue;

                        try {
                            Object value = null;
                            
                            if ("FLOAT".equalsIgnoreCase(tag.getDataType())) {
                                value = modbusClientService.readFloat(
                                    host, port, 
                                    tag.getModbusAddress(), 
                                    tag.getModbusUnitId()
                                );
                            } else if ("INT".equalsIgnoreCase(tag.getDataType()) || "INT16".equalsIgnoreCase(tag.getDataType())) {
                                value = modbusClientService.readInt16(
                                    host, port,
                                    tag.getModbusAddress(),
                                    tag.getModbusUnitId()
                                );
                            } else if ("BOOLEAN".equalsIgnoreCase(tag.getDataType())) {
                                Integer intVal = modbusClientService.readInt16(
                                    host, port,
                                    tag.getModbusAddress(),
                                    tag.getModbusUnitId()
                                );
                                value = intVal != null && intVal != 0;
                            }

                            String quality = value != null ? "GOOD" : "BAD";
                            processTagValue(tag, value, quality);

                        } catch (Exception e) {
                            log.error("Modbus read error for tag {}: {}", tag.getName(), e.getMessage());
                            eventLogService.logError("ModbusClient", "Failed to read tag " + tag.getName(), e, tag, controller);
                            processTagValue(tag, null, "BAD");
                        }

                        Thread.sleep(tag.getPollingRate());
                    }

                } catch (InterruptedException e) {
                    log.info("Modbus polling interrupted for {}", controller.getName());
                    break;
                } catch (Exception e) {
                    log.error("Modbus polling error for {}: {}", controller.getName(), e.getMessage());
                    eventLogService.logError("ModbusClient", "Polling error for " + controller.getName(), e, null, controller);
                }
            }
        });
    }

    private boolean isOpcUaTag(TagEntity tag) {
        return "OPCUA".equalsIgnoreCase(tag.getProtocol()) || 
               (tag.getNodeId() != null && !tag.getNodeId().isEmpty());
    }

    private boolean isModbusTag(TagEntity tag) {
        return "MODBUS".equalsIgnoreCase(tag.getProtocol()) || 
               (tag.getModbusAddress() != null && tag.getModbusAddress() > 0);
    }

    @Transactional
    private void processTagValue(TagEntity tag, Object value, String quality) {
        // Логируем чтение тега
        eventLogService.logTagRead(tag, value != null ? value : "NULL", quality);
        
        // Проверка на алармы (если есть min/max)
        if (value instanceof Number) {
            double numValue = ((Number) value).doubleValue();
            if (tag.getMinValue() != null && numValue < tag.getMinValue()) {
                eventLogService.logAlarm(tag, "WARNING", 
                    String.format("Low value: %.2f < %.2f %s", numValue, tag.getMinValue(), 
                        tag.getUnit() != null ? tag.getUnit() : ""),
                    tag.getMinValue(), numValue);
            }
            if (tag.getMaxValue() != null && numValue > tag.getMaxValue()) {
                eventLogService.logAlarm(tag, "WARNING",
                    String.format("High value: %.2f > %.2f %s", numValue, tag.getMaxValue(),
                        tag.getUnit() != null ? tag.getUnit() : ""),
                    tag.getMaxValue(), numValue);
            }
        }
        
        saveTelemetry(tag, value, quality);

        if (value != null) {
            telemetryProducer.sendTelemetry(tag, value, quality);
            log.info("📊 {} = {}", tag.getName(), value);
        } else {
            log.warn("⚠️ {} = NULL (quality: {})", tag.getName(), quality);
        }
    }

    private void saveTelemetry(TagEntity tag, Object value, String quality) {
        try {
            TelemetryEntity t = new TelemetryEntity();
            t.setTagId(tag.getId());
            t.setTime(Instant.now());
            t.setQuality(quality);

            if (value instanceof Number) {
                t.setValue(((Number) value).doubleValue());
            } else if (value instanceof Boolean) {
                t.setValue(((Boolean) value) ? 1.0 : 0.0);
            } else if (value != null) {
                t.setValueString(value.toString());
            }

            telemetryRepository.save(t);
        } catch (Exception e) {
            log.error("DB save error: {}", e.getMessage());
            eventLogService.logError("Database", "Failed to save telemetry for tag " + tag.getName(), e, tag, null);
        }
    }

    private Object extractValue(Variant variant) {
        if (variant == null || variant.isNull()) return null;

        Object v = variant.getValue();

        if (v instanceof UInteger) {
            return ((UInteger) v).longValue();
        }

        return v;
    }

    private String extractModbusHost(String endpoint) {
        try {
            String s = endpoint.replace("modbus://", "");
            if (s.contains(":")) {
                return s.split(":")[0];
            }
            return s;
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private int extractModbusPort(String endpoint, int defaultPort) {
        try {
            String s = endpoint.replace("modbus://", "");
            String[] parts = s.split(":");
            if (parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
            return defaultPort;
        } catch (Exception e) {
            return defaultPort;
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down all connections...");
        eventLogService.logSystem("INFO", "SCADA Gateway shutting down", Map.of("component", "OpcUaClientService"));

        for (Long id : runningStatus.keySet()) {
            runningStatus.put(id, false);
        }

        for (ExecutorService executor : executors.values()) {
            if (executor != null) {
                executor.shutdown();
            }
        }

        for (Map.Entry<Long, OpcUaClient> entry : opcClients.entrySet()) {
            if (entry.getValue() != null) {
                try {
                    entry.getValue().disconnect().get();
                    log.info("Disconnected OPC UA client for controller {}", entry.getKey());
                } catch (Exception ignored) {}
            }
        }

        modbusClientService.disconnectAll();
        log.info("Shutdown complete");
    }
}