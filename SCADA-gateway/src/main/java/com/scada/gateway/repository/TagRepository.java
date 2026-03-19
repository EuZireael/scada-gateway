package com.scada.gateway.repository;

import com.scada.gateway.model.entity.TagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TagRepository extends JpaRepository<TagEntity, Long> {
    List<TagEntity> findByControllerId(Long controllerId);
    List<TagEntity> findByControllerIdAndEnabledTrue(Long controllerId);
    List<TagEntity> findByEnabledTrue();
    
    @Query("SELECT t FROM TagEntity t JOIN FETCH t.controller WHERE t.enabled = true")
    List<TagEntity> findAllEnabledWithController();
}