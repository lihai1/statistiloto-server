package com.statistiloto.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Saved simulation results belonging to a user (identified by Keycloak sub). */
@Entity
@Table(name = "saved_simulations", schema = "app")
@Where(clause = "archived_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
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

    @Column(name = "archived_at")
    private Instant archivedAt;

    public SavedSimulation(String userSub, String requestJson, String summaryJson) {
        this.userSub = userSub;
        this.requestJson = requestJson;
        this.summaryJson = summaryJson;
        this.createdAt = Instant.now();
    }
}
