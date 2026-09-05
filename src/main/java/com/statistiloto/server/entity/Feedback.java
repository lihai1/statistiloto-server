package com.statistiloto.server.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** User feedback or lottery suggestion. */
@Entity
@Table(name = "feedback", schema = "app")
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

    public Feedback() {}

    public Feedback(String userSub, String type, String message) {
        this.userSub = userSub;
        this.type = type;
        this.message = message;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserSub() { return userSub; }
    public void setUserSub(String userSub) { this.userSub = userSub; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPage() { return page; }
    public void setPage(String page) { this.page = page; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getExtra() { return extra; }
    public void setExtra(String extra) { this.extra = extra; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
