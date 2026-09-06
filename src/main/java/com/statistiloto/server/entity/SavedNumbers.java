package com.statistiloto.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Saved lottery numbers belonging to a user (identified by Keycloak sub). */
@Entity
@Table(name = "saved_numbers", schema = "app")
@Where(clause = "archived_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
public class SavedNumbers {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_sub", nullable = false)
    private String userSub;

    @Column(name = "category", nullable = false)
    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "numbers", nullable = false, columnDefinition = "jsonb")
    private List<Integer> numbers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "will_be", columnDefinition = "jsonb")
    private List<Integer> willBe;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    public SavedNumbers(String userSub, String category, List<Integer> numbers) {
        this.userSub = userSub;
        this.category = category;
        this.numbers = numbers;
        this.createdAt = Instant.now();
    }
}
