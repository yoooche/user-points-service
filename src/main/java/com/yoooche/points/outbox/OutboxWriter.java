package com.yoooche.points.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yoooche.points.model.OutboxMessage;
import com.yoooche.points.repository.OutboxMessageRepository;
import com.yoooche.points.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxMessageRepository outboxRepository;
    private final ObjectProvider<OutboxRelayTrigger> relayTrigger;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, String tag, String messageKey, String eventId, String requestId, Object message) {
        var outbox = new OutboxMessage();
        outbox.setEventId(eventId);
        outbox.setTopic(topic);
        outbox.setTag(tag);
        outbox.setMessageKey(messageKey);
        outbox.setRequestId(requestId);
        outbox.setPayload(toJson(message, eventId));
        outbox.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        outboxRepository.save(outbox);
        doRelayAfterCommit();
    }

    private void doRelayAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                relayTrigger.ifAvailable(OutboxRelayTrigger::wakeUp);
            }
        });
    }

    private String toJson(Object message, String eventId) {
        try {
            return JsonUtils.toJson(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize message " + eventId, ex);
        }
    }
}
