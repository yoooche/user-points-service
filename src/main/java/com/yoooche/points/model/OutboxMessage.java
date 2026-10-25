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

import java.time.Instant;

@Entity
@Table(name = "outbox_message")
@Getter
@Setter
@NoArgsConstructor
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String eventId;

    @Column(nullable = false, length = 127)
    private String topic;

    @Column(nullable = false, length = 64)
    private String tag;

    @Column(name = "message_key", nullable = false, length = 64)
    private String messageKey;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(nullable = false, columnDefinition = "json")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
