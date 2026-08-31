package com.scada.gateway.repository;

import com.scada.gateway.model.entity.TagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Spring Data JPA репозиторий тегов (таблица tags). Финдеры по контроллеру и
 * включённости — для опроса и загрузки конфигурации.
 */
@Repository
public interface TagRepository extends JpaRepository<TagEntity, Long> {
    List<TagEntity> findByControllerId(Long controllerId);
    List<TagEntity> findByControllerIdAndEnabledTrue(Long controllerId);
    List<TagEntity> findByEnabledTrue();
    
    @Query("SELECT t FROM TagEntity t JOIN FETCH t.controller WHERE t.enabled = true")
    List<TagEntity> findAllEnabledWithController();
}