package com.yoooche.points.outbox;

import com.yoooche.points.TestDataCleaner;
import com.yoooche.points.TestcontainersConfiguration;
import com.yoooche.points.model.OutboxMessage;
import com.yoooche.points.repository.OutboxMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestDataCleaner.class})
class OutboxWriterIntegrationTest {

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxMessageRepository outboxRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private TestDataCleaner testDataCleaner;

    @BeforeEach
    void resetData() {
        testDataCleaner.reset();
    }

    @Test
    void storesRoutingFieldsAndSerializedMessage() {
        transactionTemplate.executeWithoutResult(status ->
                outboxWriter.append("some-topic", "SOME_TAG", "key_1", "event-1", "req-1", Map.of("a", 1)));

        OutboxMessage stored = outboxRepository.findAll().getFirst();
        assertThat(stored.getTopic()).isEqualTo("some-topic");
        assertThat(stored.getTag()).isEqualTo("SOME_TAG");
        assertThat(stored.getMessageKey()).isEqualTo("key_1");
        assertThat(stored.getEventId()).isEqualTo("event-1");
        assertThat(stored.getRequestId()).isEqualTo("req-1");
        assertThat(stored.getPayload()).isEqualToIgnoringWhitespace("{\"a\":1}");
        assertThat(stored.getCreatedAt()).isNotNull();
    }

    @Test
    void refusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> outboxWriter.append("some-topic", "TAG", "key_1", "event-1", null, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
