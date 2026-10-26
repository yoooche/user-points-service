package com.yoooche.points.outbox;

import com.yoooche.points.TestDataCleaner;
import com.yoooche.points.TestTransactionFailure;
import com.yoooche.points.TestcontainersConfiguration;
import com.yoooche.points.dto.AddPointsRequest;
import com.yoooche.points.repository.OutboxMessageRepository;
import com.yoooche.points.service.UserPointsService;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The sweep is turned off so that it cannot wake the relay between the call under test and the verification.
@SpringBootTest(properties = {"outbox.relay.enabled=true", "outbox.relay.sweep-cron=-"})
@Import({TestcontainersConfiguration.class, TestDataCleaner.class})
class OutboxRelayTriggerIntegrationTest {

    @Autowired
    private UserPointsService service;

    @Autowired
    private OutboxMessageRepository outboxRepository;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private TestDataCleaner testDataCleaner;

    @Autowired
    private TestTransactionFailure transactionFailure;

    @MockitoSpyBean
    private OutboxRelayTrigger trigger;

    @BeforeEach
    void resetData() {
        testDataCleaner.reset();
        clearInvocations(trigger);
    }

    @Test
    @SuppressWarnings("unchecked")
    void commitPublishesWithoutWaitingForTheSweep() {
        when(rocketMQTemplate.syncSend(anyString(), anyCollection())).thenReturn(sendOk());

        service.addPoints(new AddPointsRequest("user_1", 100L, "test"), java.util.UUID.randomUUID().toString());

        verify(trigger).wakeUp();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(outboxRepository.count()).isZero());
        verify(rocketMQTemplate).syncSend(eq("user-points-topic:ADDED"), anyCollection());
    }

    @Test
    void rollbackDoesNotWakeTheRelay() {
        assertThatThrownBy(() -> transactionFailure.runWithRollback(
                () -> service.addPoints(new AddPointsRequest("user_1", 100L, "test"), java.util.UUID.randomUUID().toString()),
                () -> verify(trigger, never()).wakeUp()))
                .hasMessage("force rollback");

        verify(trigger, never()).wakeUp();
    }

    private static SendResult sendOk() {
        var result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        return result;
    }
}
