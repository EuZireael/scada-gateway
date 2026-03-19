package com.scada.gateway.repository;

import com.scada.gateway.model.entity.ControllerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ControllerRepository extends JpaRepository<ControllerEntity, Long> {
    List<ControllerEntity> findByEnabledTrue();
    Optional<ControllerEntity> findByName(String name);
    Optional<ControllerEntity> findByEndpoint(String endpoint);
}