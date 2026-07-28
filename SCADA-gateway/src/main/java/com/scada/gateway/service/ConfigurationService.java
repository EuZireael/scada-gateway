package com.scada.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scada.gateway.config.OpcUaConfig;
import com.scada.gateway.model.entity.ControllerEntity;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.repository.ControllerRepository;
import com.scada.gateway.repository.TagRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Сервис для работы с конфигурацией из БД (без Lombok).
 */
@Service
public class ConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ControllerRepository controllerRepository;
    private final TagRepository tagRepository;
    private final OpcUaConfig opcUaConfig;

    private Map<Long, ControllerEntity> controllerCache = new HashMap<>();
    private Map<Long, TagEntity> tagCache = new HashMap<>();
    private Map<String, TagEntity> tagByNodeIdCache = new HashMap<>();

    // Явный конструктор (вместо @RequiredArgsConstructor)
    public ConfigurationService(ControllerRepository controllerRepository,
                                TagRepository tagRepository,
                                OpcUaConfig opcUaConfig) {
        this.controllerRepository = controllerRepository;
        this.tagRepository = tagRepository;
        this.opcUaConfig = opcUaConfig;
    }

    @PostConstruct
    @Transactional
    public void initDatabaseFromYaml() {
        // Полная синхронизация БД с YAML: добавляем новые, обновляем изменённые
        // и удаляем исчезнувшие из конфига контроллеры/теги. Идемпотентно —
        // выполняется при каждом старте, ручная чистка БД больше не нужна.
        syncDatabaseFromYaml();

        // Загружаем в кэш
        loadConfiguration();
    }

    private void syncDatabaseFromYaml() {
        log.info("Synchronizing database with YAML configuration...");

        int created = 0, updated = 0, deleted = 0;
        Set<String> yamlControllerNames = new HashSet<>();

        for (OpcUaConfig.OpcUaServerConfig serverConfig : opcUaConfig.getServers()) {
            yamlControllerNames.add(serverConfig.getName());

            // --- Контроллер: upsert по уникальному имени ---
            ControllerEntity controller = controllerRepository
                .findByName(serverConfig.getName())
                .orElseGet(ControllerEntity::new);
            boolean isNewController = controller.getId() == null;

            controller.setName(serverConfig.getName());
            controller.setEndpoint(serverConfig.getEndpoint());
            controller.setSecurityPolicy(serverConfig.getSecurity());
            controller.setUsername(serverConfig.getUsername());
            controller.setPassword(serverConfig.getPassword());
            controller.setEnabled(serverConfig.isEnabled());

            ControllerEntity savedController = controllerRepository.save(controller);
            log.info("{} controller: {} (ID {})",
                isNewController ? "Created" : "Updated",
                savedController.getName(), savedController.getId());

            // --- Теги: upsert по nodeId в пределах контроллера ---
            Map<String, TagEntity> existingByNodeId = tagRepository
                .findByControllerId(savedController.getId()).stream()
                .collect(Collectors.toMap(TagEntity::getNodeId, t -> t, (a, b) -> a));
            Set<String> yamlNodeIds = new HashSet<>();

            for (OpcUaConfig.TagConfig tagConfig : serverConfig.getTags()) {
                yamlNodeIds.add(tagConfig.getNodeId());

                TagEntity tag = existingByNodeId.get(tagConfig.getNodeId());
                if (tag == null) {
                    tag = new TagEntity();
                    tag.setController(savedController);
                    created++;
                } else {
                    updated++;
                }
                tag.setNodeId(tagConfig.getNodeId());
                tag.setName(tagConfig.getName());
                tag.setDataType(tagConfig.getDataType());
                tag.setPollingRate(tagConfig.getPollingRate());
                tag.setUnit(tagConfig.getUnit());
                tag.setEnabled(tagConfig.isEnabled());
                // Пороги для алармов: без них processTagValue не генерит ни одного аларма.
                tag.setMinValue(tagConfig.getMinValue());
                tag.setMaxValue(tagConfig.getMaxValue());
                // Связь с общей БД каналов + параметры Modbus.
                tag.setChannelId(tagConfig.getChannelId());
                // Объектная модель прибора (device={field:…}) → в Kafka metadata.
                tag.setDeviceName(tagConfig.getDeviceName());
                tag.setFieldName(tagConfig.getFieldName());
                tag.setDeviceType(tagConfig.getDeviceType());
                // Режим сырой записи прибора: карту полей сериализуем в JSON.
                tag.setRecordDevice(tagConfig.isRecordDevice());
                if (tagConfig.getFields() != null) {
                    try {
                        tag.setFieldsJson(objectMapper.writeValueAsString(tagConfig.getFields()));
                    } catch (Exception e) {
                        log.error("Не удалось сериализовать fields для {}: {}", tagConfig.getName(), e.getMessage());
                    }
                }
                tag.setProtocol(tagConfig.getProtocol());
                tag.setModbusAddress(tagConfig.getModbusAddress());
                tag.setModbusType(tagConfig.getModbusType());
                tag.setModbusUnitId(tagConfig.getModbusUnitId());

                tagRepository.save(tag);
            }

            // Теги, которых больше нет в YAML — удаляем
            List<TagEntity> staleTags = existingByNodeId.values().stream()
                .filter(t -> !yamlNodeIds.contains(t.getNodeId()))
                .collect(Collectors.toList());
            if (!staleTags.isEmpty()) {
                tagRepository.deleteAll(staleTags);
                deleted += staleTags.size();
                log.info("Deleted {} stale tags from controller {}",
                    staleTags.size(), savedController.getName());
            }
        }

        // Контроллеры, которых больше нет в YAML — удаляем вместе с их тегами
        List<ControllerEntity> staleControllers = controllerRepository.findAll().stream()
            .filter(c -> !yamlControllerNames.contains(c.getName()))
            .collect(Collectors.toList());
        for (ControllerEntity stale : staleControllers) {
            List<TagEntity> tags = tagRepository.findByControllerId(stale.getId());
            if (!tags.isEmpty()) {
                tagRepository.deleteAll(tags);
                deleted += tags.size();
            }
            controllerRepository.delete(stale);
            log.info("Deleted stale controller: {}", stale.getName());
        }

        log.info("Sync complete: {} tags created, {} updated, {} deleted; {} controllers in YAML",
            created, updated, deleted, yamlControllerNames.size());
    }

    @Transactional(readOnly = true)
    public void loadConfiguration() {
        log.info("Loading configuration from database...");

        List<ControllerEntity> controllers = controllerRepository.findByEnabledTrue();
        controllerCache = controllers.stream()
            .collect(Collectors.toMap(ControllerEntity::getId, c -> c));

        List<TagEntity> tags = tagRepository.findByEnabledTrue();
        tagCache = tags.stream()
            .collect(Collectors.toMap(TagEntity::getId, t -> t));

        tagByNodeIdCache = tags.stream()
            .filter(t -> t.getNodeId() != null)
            .collect(Collectors.toMap(TagEntity::getNodeId, t -> t));

        log.info("Configuration loaded: {} controllers, {} tags",
            controllers.size(), tags.size());
    }

    public List<ControllerEntity> getAllControllers() {
        return List.copyOf(controllerCache.values());
    }

    public List<TagEntity> getAllActiveTags() {
        return tagCache.values().stream()
            .filter(TagEntity::isEnabled)
            .collect(Collectors.toList());
    }

    public List<TagEntity> getTagsForController(Long controllerId) {
        return tagCache.values().stream()
            .filter(t -> t.getController().getId().equals(controllerId))
            .filter(TagEntity::isEnabled)
            .collect(Collectors.toList());
    }

    public TagEntity getTagByNodeId(String nodeId) {
        return tagByNodeIdCache.get(nodeId);
    }

    public Map<String, Object> getStats() {
        return Map.of(
            "controllers", controllerCache.size(),
            "tags", tagCache.size(),
            "controllers_list", controllerCache.values().stream()
                .map(c -> Map.of(
                    "id", c.getId(),
                    "name", c.getName(),
                    "endpoint", c.getEndpoint(),
                    "tags_count", getTagsForController(c.getId()).size()
                ))
                .collect(Collectors.toList())
        );
    }
}