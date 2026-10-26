package com.yoooche.points.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_points_records")
@Getter
@Setter
@NoArgsConstructor
public class UserPointsRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false)
    private long amount;

    @Column(length = 255)
    private String reason;

    @Column(name = "idempotency_key", length = 128, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", columnDefinition = "char(64)", updatable = false)
    private String requestHash;

    @Column(name = "original_response", columnDefinition = "json")
    private String originalResponse;
}
