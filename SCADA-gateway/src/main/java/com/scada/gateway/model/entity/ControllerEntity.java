package com.scada.gateway.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA-сущность контроллера (таблица controllers): endpoint (OPC UA opc.tcp / Modbus),
 * включённость, учётка, политика безопасности. Владеет тегами (1:N).
 */
@Entity
@Table(name = "controllers")
public class ControllerEntity {
    
    /** PK контроллера в БД шлюза. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Уникальное имя контроллера — ключ upsert при синхронизации с YAML. */
    @Column(nullable = false, unique = true)
    private String name;

    /** Адрес: opc.tcp://… (OPC UA) или modbus://host:port. По нему выбирается драйвер. */
    @Column(nullable = false)
    private String endpoint;

    /** Политика безопасности OPC UA (сейчас NONE — стенд без шифрования). */
    @Column(name = "security_policy")
    private String securityPolicy = "NONE";

    /** Учётка OPC UA (если сервер требует аутентификацию). */
    private String username;
    private String password;

    /** Опрашивать ли контроллер. false → пропускается при старте и супервизором. */
    @Column(name = "enabled")
    private boolean enabled = true;

    /** Необязательное описание. */
    @Column(name = "description")
    private String description;

    /** Проставляется @PrePersist при создании. */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** Проставляется @PreUpdate при изменении. */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Теги контроллера (1:N, LAZY). Каскад ALL: удаление контроллера уносит теги. */
    @OneToMany(mappedBy = "controller", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<TagEntity> tags = new ArrayList<>();

    // Пустой конструктор обязателен для JPA
    public ControllerEntity() {}

    /** JPA-хук: проставляет created_at и updated_at при первой вставке. */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /** JPA-хук: освежает updated_at при каждом обновлении. */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    
    public String getSecurityPolicy() { return securityPolicy; }
    public void setSecurityPolicy(String securityPolicy) { this.securityPolicy = securityPolicy; }
    
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    
    public List<TagEntity> getTags() { return tags; }
    public void setTags(List<TagEntity> tags) { this.tags = tags; }
}