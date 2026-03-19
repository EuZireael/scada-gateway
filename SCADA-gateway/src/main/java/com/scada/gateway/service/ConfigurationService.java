package com.scada.gateway.service;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Сервис для работы с конфигурацией из БД (без Lombok).
 */
@Service
public class ConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationService.class);

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
        if (controllerRepository.count() == 0) {
            log.info("Initializing database from YAML configuration");

            for (OpcUaConfig.OpcUaServerConfig serverConfig : opcUaConfig.getServers()) {
                ControllerEntity controller = new ControllerEntity();
                controller.setName(serverConfig.getName());
                controller.setEndpoint(serverConfig.getEndpoint());
                controller.setSecurityPolicy(serverConfig.getSecurity());
                controller.setUsername(serverConfig.getUsername());
                controller.setPassword(serverConfig.getPassword());
                controller.setEnabled(serverConfig.isEnabled());

                ControllerEntity savedController = controllerRepository.save(controller);
                log.info("Created controller: {} with ID: {}", savedController.getName(), savedController.getId());

                for (OpcUaConfig.TagConfig tagConfig : serverConfig.getTags()) {
                    TagEntity tag = new TagEntity();
                    tag.setController(savedController);
                    tag.setNodeId(tagConfig.getNodeId());
                    tag.setName(tagConfig.getName());
                    tag.setDataType(tagConfig.getDataType());
                    tag.setPollingRate(tagConfig.getPollingRate());
                    tag.setUnit(tagConfig.getUnit());
                    tag.setEnabled(tagConfig.isEnabled());

                    tagRepository.save(tag);
                    log.debug("  Created tag: {}", tag.getName());
                }
            }

            log.info("Database initialized with {} controllers and {} tags",
                controllerRepository.count(), tagRepository.count());
        }

        // Загружаем в кэш
        loadConfiguration();
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