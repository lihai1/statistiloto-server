package com.statistiloto.server.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Saved simulation results belonging to a user (identified by Keycloak sub). */
@Entity
@Table(name = "saved_simulations", schema = "app")
public class SavedSimulation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_sub", nullable = false)
    private String userSub;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
    private String requestJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary_json", nullable = false, columnDefinition = "jsonb")
    private String summaryJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SavedSimulation() {}

    public SavedSimulation(String userSub, String requestJson, String summaryJson) {
        this.userSub = userSub;
        this.requestJson = requestJson;
        this.summaryJson = summaryJson;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserSub() { return userSub; }
    public void setUserSub(String userSub) { this.userSub = userSub; }
    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String requestJson) { this.requestJson = requestJson; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
