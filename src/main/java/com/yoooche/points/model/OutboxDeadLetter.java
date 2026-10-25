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
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "outbox_dead_letter")
@Getter
@Setter
@NoArgsConstructor
public class OutboxDeadLetter {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "outbox_id", nullable = false)
    private Long outboxId;

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

    @Column(length = MAX_ERROR_LENGTH)
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "dead_at", nullable = false)
    private Instant deadAt;

    public static OutboxDeadLetter from(OutboxMessage message, String error) {
        var dead = new OutboxDeadLetter();
        dead.setOutboxId(message.getId());
        dead.setEventId(message.getEventId());
        dead.setTopic(message.getTopic());
        dead.setTag(message.getTag());
        dead.setMessageKey(message.getMessageKey());
        dead.setRequestId(message.getRequestId());
        dead.setPayload(message.getPayload());
        dead.setError(error == null || error.length() <= MAX_ERROR_LENGTH
                ? error : error.substring(0, MAX_ERROR_LENGTH));
        dead.setCreatedAt(message.getCreatedAt());
        dead.setDeadAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        return dead;
    }
}
