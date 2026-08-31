package com.scada.gateway.repository;

import com.scada.gateway.model.entity.EventLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA репозиторий журнала событий (таблица event_log). Финдеры по типу,
 * важности и тегу — для REST-эндпоинтов мониторинга.
 */
@Repository
public interface EventLogRepository extends JpaRepository<EventLogEntity, Long> {
    
    List<EventLogEntity> findByEventTypeOrderByEventTimeDesc(String eventType);
    
    List<EventLogEntity> findBySeverityOrderByEventTimeDesc(String severity);
    
    List<EventLogEntity> findByTagIdOrderByEventTimeDesc(Long tagId);
    
    @Query("SELECT e FROM EventLogEntity e WHERE e.eventTime BETWEEN :start AND :end ORDER BY e.eventTime DESC")
    List<EventLogEntity> findByTimeRange(@Param("start") Instant start, @Param("end") Instant end);
    
    @Query("SELECT e FROM EventLogEntity e WHERE e.severity = :severity AND e.acknowledged = false")
    List<EventLogEntity> findUnacknowledgedBySeverity(@Param("severity") String severity);
}