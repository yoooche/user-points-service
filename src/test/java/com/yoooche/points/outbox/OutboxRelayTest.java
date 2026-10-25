package com.yoooche.points.outbox;

import com.yoooche.points.config.OutboxProperties;
import com.yoooche.points.model.OutboxDeadLetter;
import com.yoooche.points.model.OutboxMessage;
import com.yoooche.points.mq.InvalidMessageException;
import com.yoooche.points.mq.MqMessage;
import com.yoooche.points.mq.RocketMqSender;
import com.yoooche.points.repository.OutboxDeadLetterRepository;
import com.yoooche.points.repository.OutboxMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Limit;
import org.springframework.messaging.MessagingException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final int PAGE_SIZE = 3;

    @Mock
    private OutboxMessageRepository outboxRepository;

    @Mock
    private OutboxDeadLetterRepository deadLetterRepository;

    @Mock
    private RocketMqSender sender;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        var properties = new OutboxProperties();
        properties.getRelay().setBatchSize(PAGE_SIZE);
        var transactionTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));
        relay = new OutboxRelay(outboxRepository, deadLetterRepository, sender, properties, transactionTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageIsSentInOneBatchAndDeleted() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(1, "topic-a"), message(2, "topic-a")));

        relay.publishPending();

        ArgumentCaptor<List<MqMessage>> batch = ArgumentCaptor.captor();
        verify(sender).sendBatch(eq("topic-a"), eq("ADDED"), batch.capture());
        assertThat(batch.getValue()).hasSize(2);
        verify(outboxRepository).deleteAllByIdInBatch(List.of(1L, 2L));
    }

    @Test
    void fullPagesAreReadByIdUntilTheOutboxIsExhausted() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(1, "topic-a"), message(2, "topic-a"), message(3, "topic-a")));
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(3L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(4, "topic-a")));

        relay.publishPending();

        verify(outboxRepository).deleteAllByIdInBatch(List.of(1L, 2L, 3L));
        verify(outboxRepository).deleteAllByIdInBatch(List.of(4L));
    }

    @Test
    void failedBatchStaysInTheOutboxAndDoesNotHoldBackTheOthers() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(1, "topic-a"), message(2, "topic-b")));
        doThrow(new MessagingException("no route")).when(sender).sendBatch(eq("topic-a"), anyString(), anyList());

        relay.publishPending();

        verify(outboxRepository).deleteAllByIdInBatch(List.of(2L));
        verify(outboxRepository, never()).deleteAllByIdInBatch(List.of(1L));
        verify(deadLetterRepository, never()).save(any());
    }

    @Test
    void passStopsAfterThreeFailedBatchesInARow() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(1, "topic-a"), message(2, "topic-b"), message(3, "topic-c")));
        doThrow(new MessagingException("broker down")).when(sender).sendBatch(anyString(), anyString(), anyList());

        relay.publishPending();

        verify(sender, times(3)).sendBatch(anyString(), anyString(), anyList());
        verify(outboxRepository).findByIdGreaterThanOrderByIdAsc(anyLong(), any());
        verify(outboxRepository, never()).deleteAllByIdInBatch(any());
    }

    @Test
    void successResetsTheFailureCount() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(1, "topic-a"), message(2, "topic-b"), message(3, "topic-ok")));
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(3L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(4, "topic-c"), message(5, "topic-ok")));
        doThrow(new MessagingException("no route")).when(sender)
                .sendBatch(argThat(topic -> !topic.equals("topic-ok")), anyString(), anyList());

        relay.publishPending();

        verify(outboxRepository).deleteAllByIdInBatch(List.of(3L));
        verify(outboxRepository).deleteAllByIdInBatch(List.of(5L));
    }

    @Test
    void invalidMessageIsDeadLetteredAtOnce() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(1, "topic-a")));
        doThrow(new InvalidMessageException("body too large", null)).when(sender).sendBatch(anyString(), anyString(), anyList());

        relay.publishPending();

        ArgumentCaptor<OutboxDeadLetter> dead = ArgumentCaptor.captor();
        verify(deadLetterRepository).save(dead.capture());
        assertThat(dead.getValue()).satisfies(letter -> {
            assertThat(letter.getOutboxId()).isEqualTo(1L);
            assertThat(letter.getEventId()).isEqualTo("event-1");
            assertThat(letter.getError()).isEqualTo("body too large");
        });
        verify(outboxRepository).deleteAllByIdInBatch(List.of(1L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidBatchIsSentOneByOneSoOnlyTheInvalidMessageIsDeadLettered() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(1, "topic-a"), message(2, "topic-a"), message(3, "topic-a")));
        doAnswer(invocation -> {
            List<MqMessage> batch = invocation.getArgument(2);
            if (batch.stream().anyMatch(message -> message.keys().equals("user_2"))) {
                throw new InvalidMessageException("body too large", null);
            }
            return null;
        }).when(sender).sendBatch(anyString(), anyString(), anyList());

        relay.publishPending();

        ArgumentCaptor<OutboxDeadLetter> dead = ArgumentCaptor.captor();
        verify(deadLetterRepository).save(dead.capture());
        assertThat(dead.getValue().getOutboxId()).isEqualTo(2L);
        verify(outboxRepository).deleteAllByIdInBatch(List.of(1L));
        verify(outboxRepository).deleteAllByIdInBatch(List.of(2L));
        verify(outboxRepository).deleteAllByIdInBatch(List.of(3L));
    }

    @Test
    void batchRefusedAsAWholeIsPublishedOneByOneWhenNoMessageIsInvalid() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(1, "topic-a"), message(2, "topic-a")));
        doAnswer(invocation -> {
            List<MqMessage> batch = invocation.getArgument(2);
            if (batch.size() > 1) {
                throw new InvalidMessageException("batch over max size", null);
            }
            return null;
        }).when(sender).sendBatch(anyString(), anyString(), anyList());

        relay.publishPending();

        verify(outboxRepository).deleteAllByIdInBatch(List.of(1L));
        verify(outboxRepository).deleteAllByIdInBatch(List.of(2L));
        verify(deadLetterRepository, never()).save(any());
    }

    @Test
    void outageDuringTheOneByOneSendsEndsThem() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(1, "topic-a"), message(2, "topic-a"), message(3, "topic-a")));
        doThrow(new InvalidMessageException("body too large", null))
                .doThrow(new MessagingException("broker down"))
                .when(sender).sendBatch(anyString(), anyString(), anyList());

        relay.publishPending();

        verify(sender, times(2)).sendBatch(anyString(), anyString(), anyList());
        verify(outboxRepository, never()).deleteAllByIdInBatch(any());
        verify(deadLetterRepository, never()).save(any());
    }

    @Test
    void failedDeadLetterMoveKeepsTheMessageInTheOutbox() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(1, "topic-a")));
        doThrow(new InvalidMessageException("body too large", null)).when(sender).sendBatch(anyString(), anyString(), anyList());
        when(deadLetterRepository.save(any())).thenThrow(new QueryTimeoutException("db down"));

        relay.publishPending();

        verify(outboxRepository, never()).deleteAllByIdInBatch(any());
    }

    @Test
    void transactionFailureIsCaughtLikeADatabaseFailure() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(anyLong(), any()))
                .thenThrow(new CannotCreateTransactionException("db down"));

        relay.publishPending();

        verify(sender, never()).sendBatch(anyString(), anyString(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchesAreSplitBySize() {
        String halfMegabyte = "{\"a\":\"" + "x".repeat(512 * 1024) + "\"}";
        OutboxMessage first = message(1, "topic-a");
        OutboxMessage second = message(2, "topic-a");
        first.setPayload(halfMegabyte);
        second.setPayload(halfMegabyte);
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE))).thenReturn(List.of(first, second));

        relay.publishPending();

        ArgumentCaptor<List<MqMessage>> batches = ArgumentCaptor.captor();
        verify(sender, times(2)).sendBatch(eq("topic-a"), eq("ADDED"), batches.capture());
        assertThat(batches.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSize(1));
    }

    @Test
    void messagesAreSentAgainWhenDeletingThemFails() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(message(1, "topic-a")));
        doThrow(new QueryTimeoutException("db down")).doNothing()
                .when(outboxRepository).deleteAllByIdInBatch(List.of(1L));

        relay.publishPending();
        relay.publishPending();

        verify(sender, times(2)).sendBatch(eq("topic-a"), anyString(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void keysAreTheMessageKeyAndTheRequestId() {
        OutboxMessage withRequest = message(1, "topic-a");
        withRequest.setRequestId("req-1");
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(0L, Limit.of(PAGE_SIZE)))
                .thenReturn(List.of(withRequest, message(2, "topic-a")));

        relay.publishPending();

        ArgumentCaptor<List<MqMessage>> batch = ArgumentCaptor.captor();
        verify(sender).sendBatch(anyString(), anyString(), batch.capture());
        assertThat(batch.getValue()).extracting(MqMessage::keys).containsExactly("user_1 req-1", "user_2");
    }

    @Test
    void emptyOutboxSendsNothing() {
        when(outboxRepository.findByIdGreaterThanOrderByIdAsc(anyLong(), any())).thenReturn(List.of());

        relay.publishPending();

        verify(sender, never()).sendBatch(anyString(), anyString(), anyList());
    }

    private static OutboxMessage message(long id, String topic) {
        var message = new OutboxMessage();
        message.setId(id);
        message.setEventId("event-" + id);
        message.setTopic(topic);
        message.setTag("ADDED");
        message.setMessageKey("user_" + id);
        message.setPayload("{}");
        return message;
    }
}
