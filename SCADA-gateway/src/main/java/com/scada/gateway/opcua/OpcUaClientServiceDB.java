package com.scada.gateway.opcua;

import com.scada.gateway.model.entity.ControllerEntity;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.model.entity.TelemetryEntity;
import com.scada.gateway.model.TagValue;
import com.scada.gateway.service.ConfigurationService;
import com.scada.gateway.repository.TelemetryRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * OPC UA клиент, работающий с конфигурацией из БД (без Lombok).
 */
@Service
public class OpcUaClientServiceDB {

    private static final Logger log = LoggerFactory.getLogger(OpcUaClientServiceDB.class);

    private final ConfigurationService configurationService;
    private final TelemetryRepository telemetryRepository;

    private OpcUaClient client;
    private ExecutorService executorService;
    private volatile boolean running = false;
    private Map<Long, TagEntity> tagCache = new ConcurrentHashMap<>();

    // Явный конструктор
    public OpcUaClientServiceDB(ConfigurationService configurationService,
                                TelemetryRepository telemetryRepository) {
        this.configurationService = configurationService;
        this.telemetryRepository = telemetryRepository;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing OPC UA Client Service with Database configuration");
        this.executorService = Executors.newSingleThreadExecutor();

        // Загружаем конфигурацию из БД
        loadConfiguration();

        // Подключаемся к первому активному контроллеру
        List<ControllerEntity> controllers = configurationService.getAllControllers();
        if (!controllers.isEmpty()) {
            connectToServer(controllers.get(0));
        } else {
            log.warn("No active controllers found in database");
        }
    }

    private void loadConfiguration() {
        var tags = configurationService.getAllActiveTags();
        tags.forEach(tag -> tagCache.put(tag.getId(), tag));
        log.info("Loaded {} tags from database", tagCache.size());
    }

    private void connectToServer(ControllerEntity controller) {
        try {
            log.info("Connecting to OPC UA server: {} at {}",
                    controller.getName(), controller.getEndpoint());

            List<EndpointDescription> endpoints = DiscoveryClient.getEndpoints(
                    controller.getEndpoint()).get();

            EndpointDescription endpoint = endpoints.stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No endpoints found"));

            OpcUaClientConfig config = OpcUaClientConfig.builder()
                    .setApplicationName(LocalizedText.english("SCADA Gateway"))
                    .setApplicationUri("urn:scada:gateway")
                    .setEndpoint(endpoint)
                    .build();

            client = OpcUaClient.create(config);
            client.connect().get();

            log.info("✅ Connected to OPC UA server: {}", controller.getName());

            running = true;
            startPolling(controller);

        } catch (Exception e) {
            log.error("Failed to connect to OPC UA server: {}", e.getMessage());
        }
    }

    private void startPolling(ControllerEntity controller) {
        executorService.submit(() -> {
            while (running) {
                try {
                    List<TagEntity> tags = configurationService.getTagsForController(controller.getId());

                    for (TagEntity tag : tags) {
                        if (!tag.isEnabled()) continue;

                        readTag(controller, tag);
                        Thread.sleep(tag.getPollingRate());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in polling loop: {}", e.getMessage());
                }
            }
        });
    }

    private void readTag(ControllerEntity controller, TagEntity tag) {
        try {
            NodeId nodeId = NodeId.parse(tag.getNodeId());

            DataValue dataValue = client.readValue(0, TimestampsToReturn.Both, nodeId).get();

            if (dataValue.getStatusCode().isGood()) {
                Object value = extractValue(dataValue.getValue());

                // Сохраняем последнее значение в кэш
                tag.setLastValue(value);
                tag.setLastReadTime(Instant.now());

                // Сохраняем в БД телеметрии
                saveTelemetry(tag, value, "GOOD");

                // Создаем TagValue для логирования
                TagValue tagValue = new TagValue(
                        controller.getId().toString(),
                        tag.getNodeId(),
                        tag.getName(),
                        value,
                        tag.getDataType(),
                        "GOOD",
                        Instant.now(),
                        tag.getUnit()
                );

                log.info("📊 {} = {} {}",
                        tag.getName(),
                        value,
                        tag.getUnit() != null ? tag.getUnit() : "");

            } else {
                log.warn("Bad status for {}: {}", tag.getNodeId(), dataValue.getStatusCode());
                saveTelemetry(tag, null, "BAD");
            }

        } catch (Exception e) {
            log.error("Error reading tag {}: {}", tag.getNodeId(), e.getMessage());
        }
    }

    private void saveTelemetry(TagEntity tag, Object value, String quality) {
        try {
            TelemetryEntity telemetry = new TelemetryEntity();
            telemetry.setTagId(tag.getId());
            telemetry.setTime(Instant.now());
            telemetry.setQuality(quality);

            if (value instanceof Number) {
                telemetry.setValue(((Number) value).doubleValue());
            } else if (value instanceof Boolean) {
                telemetry.setValueString(value.toString());
            } else if (value != null) {
                telemetry.setValueString(value.toString());
            }

            telemetryRepository.save(telemetry);

        } catch (Exception e) {
            log.error("Failed to save telemetry for tag {}: {}", tag.getName(), e.getMessage());
        }
    }

    private Object extractValue(Variant variant) {
        if (variant == null || variant.isNull()) {
            return null;
        }

        Object value = variant.getValue();
        if (value instanceof UInteger) {
            return ((UInteger) value).longValue();
        }
        return value;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down OPC UA client...");
        running = false;

        if (executorService != null) {
            executorService.shutdown();
            try {
                executorService.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (client != null) {
            try {
                client.disconnect().get();
                log.info("Disconnected from OPC UA server");
            } catch (Exception e) {
                log.error("Error disconnecting: {}", e.getMessage());
            }
        }
    }

    public Map<Long, TagEntity> getLastValues() {
        return tagCache;
    }

    public boolean isConnected() {
        return client != null && running;
    }
}