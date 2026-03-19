package com.scada.gateway.repository;

import com.scada.gateway.model.entity.TelemetryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;

@Repository
public interface TelemetryRepository extends JpaRepository<TelemetryEntity, Long> {
    List<TelemetryEntity> findTop10ByTagIdOrderByTimeDesc(Long tagId);
    List<TelemetryEntity> findByTagIdAndTimeBetweenOrderByTimeAsc(
        Long tagId, Instant start, Instant end);
    
    @Query("SELECT AVG(t.value) FROM TelemetryEntity t WHERE t.tagId = :tagId AND t.time BETWEEN :start AND :end")
    Double averageValue(@Param("tagId") Long tagId, 
                        @Param("start") Instant start, 
                        @Param("end") Instant end);
}