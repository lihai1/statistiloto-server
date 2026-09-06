package com.statistiloto.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** User profile keyed by the Keycloak subject (sub) claim. */
@Entity
@Table(name = "user_profile", schema = "app")
@Getter
@Setter
@NoArgsConstructor
public class UserProfile {

    @Id
    @Column(name = "sub", nullable = false, updatable = false)
    private String sub;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "archive_from")
    private LocalDate archiveFrom;

    @Column(name = "archive_to")
    private LocalDate archiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    public UserProfile(String sub, String displayName) {
        this.sub = sub;
        this.displayName = displayName;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
