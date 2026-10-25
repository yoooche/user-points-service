package com.yoooche.points.outbox;

import com.yoooche.points.config.OutboxProperties;
import com.yoooche.points.model.OutboxDeadLetter;
import com.yoooche.points.model.OutboxMessage;
import com.yoooche.points.mq.InvalidMessageException;
import com.yoooche.points.mq.MqMessage;
import com.yoooche.points.mq.RocketMqSender;
import com.yoooche.points.repository.OutboxDeadLetterRepository;
import com.yoooche.points.repository.OutboxMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final int MAX_BATCH_BYTES = 1024 * 1024;
    private static final int MAX_FAILED_BATCHES_IN_A_ROW = 3;

    private final OutboxMessageRepository outboxRepository;
    private final OutboxDeadLetterRepository deadLetterRepository;
    private final RocketMqSender sender;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;

    public void publishPending() {
        int pageSize = properties.getRelay().getBatchSize();
        int failedInARow = 0;
        try {
            // Keyset paging: rows that fail stay in the table, so re-reading from the start would loop on them.
            long lastId = 0;
            List<OutboxMessage> page;
            do {
                page = outboxRepository.findByIdGreaterThanOrderByIdAsc(lastId, Limit.of(pageSize));
                for (List<OutboxMessage> batch : batches(page)) {
                    if (publish(batch)) {
                        failedInARow = 0;
                    } else if (++failedInARow == MAX_FAILED_BATCHES_IN_A_ROW) {
                        log.warn("Stopping after {} failed batches in a row; the rest waits for the next pass", failedInARow);
                        return;
                    }
                }
                if (!page.isEmpty()) {
                    lastId = page.getLast().getId();
                }
            } while (page.size() == pageSize);
        } catch (DataAccessException | TransactionException ex) {
            log.warn("Outbox unavailable, will retry: {}", ex.getMessage());
        }
    }

    private boolean publish(List<OutboxMessage> batch) {
        OutboxMessage first = batch.getFirst();
        try {
            sender.sendBatch(first.getTopic(), first.getTag(), batch.stream().map(OutboxRelay::toMqMessage).toList());
        } catch (InvalidMessageException ex) {
            if (batch.size() == 1) {
                deadLetter(first, ex.getMessage());
                return true;
            }
            // One invalid message rejects the whole batch, so the messages are sent one by one to find it. Any other
            // failure ends the split, or an outage would make every remaining message wait for the send timeout.
            for (OutboxMessage message : batch) {
                if (!publish(List.of(message))) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException ex) {
            log.warn("Failed to publish {} messages to {}, will retry: {}", batch.size(), first.getTopic(), ex.getMessage());
            return false;
        }
        outboxRepository.deleteAllByIdInBatch(batch.stream().map(OutboxMessage::getId).toList());
        return true;
    }

    private static List<List<OutboxMessage>> batches(List<OutboxMessage> page) {
        var byDestination = new LinkedHashMap<String, List<OutboxMessage>>();
        page.forEach(message -> byDestination
                .computeIfAbsent(message.getTopic() + ":" + message.getTag(), destination -> new ArrayList<>())
                .add(message));

        var batches = new ArrayList<List<OutboxMessage>>();
        for (List<OutboxMessage> messages : byDestination.values()) {
            var batch = new ArrayList<OutboxMessage>();
            var bytes = 0;
            for (OutboxMessage message : messages) {
                var size = message.getPayload().getBytes(StandardCharsets.UTF_8).length;
                if (!batch.isEmpty() && bytes + size > MAX_BATCH_BYTES) {
                    batches.add(batch);
                    batch = new ArrayList<>();
                    bytes = 0;
                }
                batch.add(message);
                bytes += size;
            }
            batches.add(batch);
        }
        return batches;
    }

    private void deadLetter(OutboxMessage message, String error) {
        transactionTemplate.executeWithoutResult(status -> {
            deadLetterRepository.save(OutboxDeadLetter.from(message, error));
            outboxRepository.deleteAllByIdInBatch(List.of(message.getId()));
        });
        log.error("Moved event {} to the dead letter table: {}", message.getEventId(), error);
    }

    private static MqMessage toMqMessage(OutboxMessage message) {
        var keys = message.getRequestId() == null
                ? message.getMessageKey()
                : message.getMessageKey() + " " + message.getRequestId();
        return new MqMessage(keys, message.getPayload());
    }
}
