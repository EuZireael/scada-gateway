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
    /** Все теги контроллера (включая выключенные) — для синхронизации с YAML. */
    List<TagEntity> findByControllerId(Long controllerId);
    /** Только включённые теги контроллера — для опроса. */
    List<TagEntity> findByControllerIdAndEnabledTrue(Long controllerId);
    /** Все включённые теги — источник для кэшей ConfigurationService. */
    List<TagEntity> findByEnabledTrue();

    /** Включённые теги СРАЗУ с контроллером (JOIN FETCH) — гасит N+1 и
     *  LazyInitializationException при доступе к controller вне транзакции. */
    @Query("SELECT t FROM TagEntity t JOIN FETCH t.controller WHERE t.enabled = true")
    List<TagEntity> findAllEnabledWithController();
}