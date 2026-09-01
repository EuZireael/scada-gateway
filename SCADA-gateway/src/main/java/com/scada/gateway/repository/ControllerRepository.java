package com.scada.gateway.repository;

import com.scada.gateway.model.entity.ControllerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA репозиторий контроллеров (таблица controllers).
 * findByEnabledTrue — включённые контроллеры для опроса.
 */
@Repository
public interface ControllerRepository extends JpaRepository<ControllerEntity, Long> {
    /** Включённые контроллеры — те, что шлюз реально опрашивает. */
    List<ControllerEntity> findByEnabledTrue();
    /** Поиск по уникальному имени — upsert при синхронизации с YAML. */
    Optional<ControllerEntity> findByName(String name);
    /** Поиск по endpoint — вспомогательный доступ по адресу. */
    Optional<ControllerEntity> findByEndpoint(String endpoint);
}