package com.scada.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scada.gateway.kafka.producer.EventProducer;
import com.scada.gateway.model.entity.EventLogEntity;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.model.entity.ControllerEntity;
import com.scada.gateway.repository.EventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Журнал событий шлюза. Типизированные методы (logSystem/logEvent/logConnection/
 * logAlarm/logError) пишут запись в таблицу event_log — единая точка аудита связи,
 * качества, алармов и ошибок.
 */
@Service
public class EventLogService {

    private static final Logger log = LoggerFactory.getLogger(EventLogService.class);
    
    private final EventLogRepository eventLogRepository;
    private final EventProducer eventProducer;
    private final ObjectMapper objectMapper;

    public EventLogService(EventLogRepository eventLogRepository,
                           EventProducer eventProducer) {
        this.eventLogRepository = eventLogRepository;
        this.eventProducer = eventProducer;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Логирование системного события
     */
    @Transactional
    public void logSystem(String severity, String message, Map<String, Object> details) {
        saveEvent("SYSTEM", "System", severity, message, null, null, null, details);
        eventProducer.sendEvent("SYSTEM", "System", severity, message, details);
        // Также пишем в обычный лог
        if ("ERROR".equals(severity)) {
            log.error("SYSTEM EVENT [{}]: {}", severity, message);
        } else if ("WARNING".equals(severity)) {
            log.warn("SYSTEM EVENT [{}]: {}", severity, message);
        } else {
            log.info("SYSTEM EVENT [{}]: {}", severity, message);
        }
    }

    /**
     * Универсальное событие произвольного типа: пишет в БД и публикует в Kafka.
     * Используется для HEARTBEAT, QUALITY_CHANGE и прочих событий шлюза.
     */
    @Transactional
    public void logEvent(String eventType, String source, String severity,
                         String message, Map<String, Object> details) {
        saveEvent(eventType, source, severity, message, null, null, null, details);
        eventProducer.sendEvent(eventType, source, severity, message, details);
    }

    /**
     * Логирование события подключения (упрощенная версия без ControllerEntity)
     */
    @Transactional
    public void logConnection(String source, String endpoint, boolean connected) {
        Map<String, Object> details = new HashMap<>();
        details.put("endpoint", endpoint);
        details.put("connected", connected);
        
        String status = connected ? "CONNECTED" : "DISCONNECTED";
        String severity = connected ? "INFO" : "WARNING";
        String message = String.format("%s to %s", status, endpoint);

        saveEvent("CONNECTION", source, severity, message, null, null, null, details);
        eventProducer.sendEvent("CONNECTION", source, severity, message, details);
        log.info("🔌 {}", message);
    }

    /**
     * Логирование события подключения (с ControllerEntity)
     */
    @Transactional
    public void logConnection(ControllerEntity controller, String status, String details) {
        Map<String, Object> detailsMap = new HashMap<>();
        detailsMap.put("endpoint", controller.getEndpoint());
        detailsMap.put("status", status);
        if (details != null) {
            detailsMap.put("details", details);
        }
        
        String message = String.format("Connection to %s: %s", controller.getName(), status);
        String severity = "ERROR".equals(status) ? "ERROR" : "INFO";
        saveEvent("CONNECTION", "OpcUaClient",
                  severity,
                  message, null, controller.getId(), null, detailsMap);
        eventProducer.sendEvent("CONNECTION", "OpcUaClient", severity, message, detailsMap);

        log.info("🔌 {}", message);
    }

    /**
     * Логирование чтения тега
     */
    @Transactional
    public void logTagRead(TagEntity tag, Object value, String quality) {
        Map<String, Object> details = new HashMap<>();
        details.put("value", value);
        details.put("quality", quality);
        details.put("nodeId", tag.getNodeId());
        details.put("modbusAddress", tag.getModbusAddress());
        
        String message = String.format("Tag read: %s = %s %s [%s]", 
            tag.getName(), value, 
            tag.getUnit() != null ? tag.getUnit() : "", 
            quality);
        
        saveEvent("TAG_READ", "ClientService", "INFO", message, 
                  tag.getId(), tag.getController() != null ? tag.getController().getId() : null, 
                  null, details);
        
        log.debug("📊 {}", message);
    }

    /**
     * Логирование аларма
     */
    @Transactional
    public void logAlarm(TagEntity tag, String severity, String message, Double threshold, Double currentValue) {
        Map<String, Object> details = new HashMap<>();
        details.put("threshold", threshold);
        details.put("currentValue", currentValue);
        details.put("tagName", tag.getName());
        details.put("unit", tag.getUnit());
        
        saveEvent("ALARM", "ClientService", severity, message, 
                  tag.getId(), tag.getController() != null ? tag.getController().getId() : null, 
                  null, details);
        
        if ("CRITICAL".equals(severity)) {
            log.error("🚨 ALARM [{}]: {}", severity, message);
        } else if ("MAJOR".equals(severity)) {
            log.warn("🚨 ALARM [{}]: {}", severity, message);
        } else {
            log.warn("⚠️ ALARM [{}]: {}", severity, message);
        }
    }

    /**
     * Логирование ошибок (упрощенная версия)
     */
    @Transactional
    public void logError(String source, String message) {
        Map<String, Object> details = new HashMap<>();
        details.put("source", source);

        saveEvent("ERROR", source, "ERROR", message, null, null, null, details);
        eventProducer.sendEvent("ERROR", source, "ERROR", message, details);
        log.error("❌ ERROR [{}]: {}", source, message);
    }

    /**
     * Логирование ошибок (полная версия)
     */
    @Transactional
    public void logError(String source, String message, Exception e, TagEntity tag, ControllerEntity controller) {
        Map<String, Object> details = new HashMap<>();
        details.put("errorMessage", e != null ? e.getMessage() : null);
        details.put("errorType", e != null ? e.getClass().getSimpleName() : null);
        
        if (e != null && e.getStackTrace() != null && e.getStackTrace().length > 0) {
            details.put("stackTrace", e.getStackTrace()[0].toString());
        }
        
        saveEvent("ERROR", source, "ERROR", message,
                  tag != null ? tag.getId() : null,
                  controller != null ? controller.getId() : null,
                  null, details);
        eventProducer.sendEvent("ERROR", source, "ERROR", message, details);

        log.error("❌ ERROR [{}]: {}", source, message, e);
    }

    /**
     * Логирование событий Kafka
     */
    @Transactional
    public void logKafkaEvent(String eventType, String topic, String key, String status, String details) {
        Map<String, Object> detailsMap = new HashMap<>();
        detailsMap.put("topic", topic);
        detailsMap.put("key", key);
        detailsMap.put("status", status);
        if (details != null) {
            detailsMap.put("details", details);
        }
        
        String message = String.format("Kafka %s: topic=%s, key=%s - %s", eventType, topic, key, status);
        saveEvent("KAFKA", "KafkaProducer", "ERROR".equals(status) ? "ERROR" : "INFO", 
                  message, null, null, null, detailsMap);
        
        if ("ERROR".equals(status)) {
            log.error("📨 {}", message);
        } else {
            log.debug("📨 {}", message);
        }
    }

    /**
     * Сохранение события в БД
     */
    private void saveEvent(String eventType, String source, String severity, String message,
                          Long tagId, Long controllerId, String userId, Map<String, Object> details) {
        try {
            EventLogEntity event = new EventLogEntity();
            event.setEventTime(Instant.now());
            event.setEventType(eventType);
            event.setSource(source);
            event.setSeverity(severity);
            event.setMessage(message);
            event.setTagId(tagId);
            event.setControllerId(controllerId);
            event.setUserId(userId);
            event.setAcknowledged(false);
            
            if (details != null && !details.isEmpty()) {
                event.setDetails(objectMapper.writeValueAsString(details));
            }
            
            eventLogRepository.save(event);
            
        } catch (Exception e) {
            log.error("Failed to save event log: {}", e.getMessage());
        }
    }

    /**
     * Получение последних событий
     */
    public List<EventLogEntity> getRecentEvents(int limit) {
        return eventLogRepository.findAll().stream()
                .sorted((a, b) -> b.getEventTime().compareTo(a.getEventTime()))
                .limit(limit)
                .toList();
    }

    /**
     * Получение событий по типу
     */
    public List<EventLogEntity> getEventsByType(String eventType) {
        return eventLogRepository.findByEventTypeOrderByEventTimeDesc(eventType);
    }

    /**
     * Получение событий по severity (INFO, WARNING, ERROR, CRITICAL)
     */
    public List<EventLogEntity> getEventsBySeverity(String severity) {
        return eventLogRepository.findBySeverityOrderByEventTimeDesc(severity);
    }

    /**
     * Получение событий по тегу
     */
    public List<EventLogEntity> getEventsByTag(Long tagId) {
        return eventLogRepository.findByTagIdOrderByEventTimeDesc(tagId);
    }

    /**
     * Получение событий за период
     */
    public List<EventLogEntity> getEventsByTimeRange(Instant start, Instant end) {
        return eventLogRepository.findByTimeRange(start, end);
    }

    /**
     * Получение неподтверждённых алармов
     */
    public List<EventLogEntity> getUnacknowledgedAlarms() {
        return eventLogRepository.findUnacknowledgedBySeverity("CRITICAL");
    }

    /**
     * Получение всех алармов
     */
    public List<EventLogEntity> getAllAlarms() {
        return eventLogRepository.findByEventTypeOrderByEventTimeDesc("ALARM");
    }

    /**
     * Подтверждение аларма
     */
    @Transactional
    public void acknowledgeAlarm(Long eventId, String userId) {
        EventLogEntity event = eventLogRepository.findById(eventId).orElse(null);
        if (event != null && "ALARM".equals(event.getEventType())) {
            event.setAcknowledged(true);
            event.setUserId(userId);
            eventLogRepository.save(event);
            log.info("✅ Alarm acknowledged: {}", event.getMessage());
        }
    }
}