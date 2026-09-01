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
    
    /** События одного типа (CONNECTION/ALARM/…), новые сверху. */
    List<EventLogEntity> findByEventTypeOrderByEventTimeDesc(String eventType);

    /** События одной важности (INFO/WARNING/ERROR/CRITICAL), новые сверху. */
    List<EventLogEntity> findBySeverityOrderByEventTimeDesc(String severity);

    /** События по конкретному тегу, новые сверху. */
    List<EventLogEntity> findByTagIdOrderByEventTimeDesc(Long tagId);

    /** События за интервал времени [start; end], новые сверху. */
    @Query("SELECT e FROM EventLogEntity e WHERE e.eventTime BETWEEN :start AND :end ORDER BY e.eventTime DESC")
    List<EventLogEntity> findByTimeRange(@Param("start") Instant start, @Param("end") Instant end);

    /** Неподтверждённые (acknowledged=false) события заданной важности — активные алармы. */
    @Query("SELECT e FROM EventLogEntity e WHERE e.severity = :severity AND e.acknowledged = false")
    List<EventLogEntity> findUnacknowledgedBySeverity(@Param("severity") String severity);
}