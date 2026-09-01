package com.scada.gateway.repository;

import com.scada.gateway.model.entity.TelemetryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA репозиторий телеметрии (таблица telemetry). saveAll — батч-вставка
 * точек цикла; финдеры для истории значений по тегу и интервалу времени.
 */
@Repository
public interface TelemetryRepository extends JpaRepository<TelemetryEntity, Long> {
    /** Последние 10 точек тега (новые сверху) — быстрый предпросмотр значения. */
    List<TelemetryEntity> findTop10ByTagIdOrderByTimeDesc(Long tagId);
    /** История тега за интервал по возрастанию времени — для построения графика. */
    List<TelemetryEntity> findByTagIdAndTimeBetweenOrderByTimeAsc(
        Long tagId, Instant start, Instant end);

    /** Среднее значение тега за интервал (агрегат на стороне БД). */
    @Query("SELECT AVG(t.value) FROM TelemetryEntity t WHERE t.tagId = :tagId AND t.time BETWEEN :start AND :end")
    Double averageValue(@Param("tagId") Long tagId,
                        @Param("start") Instant start,
                        @Param("end") Instant end);
}