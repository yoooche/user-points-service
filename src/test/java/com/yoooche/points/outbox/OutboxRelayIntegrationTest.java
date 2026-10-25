package com.yoooche.points.outbox;

import com.yoooche.points.TestDataCleaner;
import com.yoooche.points.TestcontainersConfiguration;
import com.yoooche.points.model.OutboxDeadLetter;
import com.yoooche.points.model.OutboxMessage;
import com.yoooche.points.repository.OutboxDeadLetterRepository;
import com.yoooche.points.repository.OutboxMessageRepository;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.MessagingException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestDataCleaner.class})
class OutboxRelayIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutboxMessageRepository outboxRepository;

    @Autowired
    private OutboxDeadLetterRepository deadLetterRepository;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private TestDataCleaner testDataCleaner;

    @BeforeEach
    void resetData() {
        testDataCleaner.reset();
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusedMessageMovesToTheDeadLetterTableAndTheOthersAreSent() {
        when(rocketMQTemplate.syncSend(eq("bad-topic:TAG"), anyCollection()))
                .thenThrow(new MessagingException("send failed", new MQClientException(ResponseCode.MESSAGE_ILLEGAL, "body too large")));
        when(rocketMQTemplate.syncSend(eq("good-topic:TAG"), anyCollection())).thenReturn(sendOk());
        append("bad-topic", "event-bad");
        append("good-topic", "event-good");

        relay.publishPending();

        assertThat(outboxRepository.count()).isZero();
        assertThat(deadLetterRepository.findAll()).singleElement().satisfies(dead -> {
            assertThat(dead.getEventId()).isEqualTo("event-bad");
            assertThat(dead.getError()).contains("body too large");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void outageKeepsEveryMessageInTheOutbox() {
        when(rocketMQTemplate.syncSend(eq("good-topic:TAG"), anyCollection())).thenThrow(new MessagingException("broker down"));
        append("good-topic", "event-1");

        relay.publishPending();

        assertThat(outboxRepository.findAll()).extracting(OutboxMessage::getEventId).containsExactly("event-1");
        assertThat(deadLetterRepository.count()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayedEventCanBeDeadLetteredAgain() {
        when(rocketMQTemplate.syncSend(eq("bad-topic:TAG"), anyCollection()))
                .thenThrow(new MessagingException("send failed", new MQClientException(ResponseCode.MESSAGE_ILLEGAL, "body too large")));
        append("bad-topic", "event-bad");
        relay.publishPending();
        append("bad-topic", "event-bad");

        relay.publishPending();

        assertThat(deadLetterRepository.findAll()).extracting(OutboxDeadLetter::getEventId)
                .containsExactly("event-bad", "event-bad");
    }

    private void append(String topic, String eventId) {
        transactionTemplate.executeWithoutResult(status ->
                writer.append(topic, "TAG", "user_1", eventId, null, Map.of("id", eventId)));
    }

    private static SendResult sendOk() {
        var result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        return result;
    }
}
