package com.statistiloto.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** User feedback or lottery suggestion. */
@Entity
@Table(name = "feedback", schema = "app")
@Where(clause = "archived_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_sub")
    private String userSub;

    @Column(name = "type", nullable = false)
    private String type = "general";

    @Column(name = "status", nullable = false)
    private String status = "new";

    @Column(name = "page")
    private String page;

    @Column(name = "language")
    private String language;

    @Column(name = "tier")
    private String tier;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra", columnDefinition = "jsonb")
    private String extra;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    public Feedback(String userSub, String type, String message) {
        this.userSub = userSub;
        this.type = type;
        this.message = message;
        this.createdAt = Instant.now();
    }
}
