package com.yoooche.points.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoooche.points.TestDataCleaner;
import com.yoooche.points.TestcontainersConfiguration;
import com.yoooche.points.dto.AddPointsRequest;
import com.yoooche.points.model.OutboxMessage;
import com.yoooche.points.outbox.OutboxRelay;
import com.yoooche.points.repository.OutboxMessageRepository;
import com.yoooche.points.repository.UserPointsRepository;
import com.yoooche.points.service.UserPointsService;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestDataCleaner.class})
class PointsEventOutboxIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserPointsService service;

    @Autowired
    private OutboxMessageRepository outboxRepository;

    @Autowired
    private UserPointsRepository pointsRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestDataCleaner testDataCleaner;

    @BeforeEach
    void resetData() {
        testDataCleaner.reset();
    }

    @Test
    void addingPointsWritesAddedEvent() throws Exception {
        addPoints("user_1", 100, "req-1").andExpect(status().isCreated());

        OutboxMessage outbox = outboxRows().getFirst();
        JsonNode payload = objectMapper.readTree(outbox.getPayload());
        assertThat(outbox.getTopic()).isEqualTo("user-points-topic");
        assertThat(outbox.getTag()).isEqualTo("ADDED");
        assertThat(outbox.getMessageKey()).isEqualTo("user_1");
        assertThat(outbox.getRequestId()).isEqualTo("req-1");
        assertThat(payload.get("eventId").asText()).isEqualTo(outbox.getEventId());
        assertThat(payload.get("userId").asText()).isEqualTo("user_1");
        assertThat(payload.get("amount").asLong()).isEqualTo(100);
        assertThat(payload.get("total").asLong()).isEqualTo(100);
        assertThat(payload.get("requestId").asText()).isEqualTo("req-1");
        assertThat(payload.hasNonNull("occurredAt")).isTrue();
    }

    @Test
    void deletingUserWritesNoEvent() throws Exception {
        addPoints("user_1", 100, "req-1");
        mockMvc.perform(delete("/points/user_1").header("X-Request-Id", "req-2"))
                .andExpect(status().isNoContent());

        assertThat(outboxRows()).extracting(OutboxMessage::getRequestId).containsExactly("req-1");
    }

    @Test
    void failedChangeWritesNoEvent() throws Exception {
        service.addPoints(new AddPointsRequest("rich_user", Long.MAX_VALUE, "seed"));

        addPoints("rich_user", 1, "req-1").andExpect(status().isUnprocessableEntity());
        mockMvc.perform(delete("/points/nobody")).andExpect(status().isNotFound());

        assertThat(outboxRows()).hasSize(1);
    }

    @Test
    void eventIsRolledBackWithTheChangeThatWroteIt() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            service.addPoints(new AddPointsRequest("user_1", 100L, "test"));
            assertThat(outboxRepository.count()).isEqualTo(1);
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");

        assertThat(outboxRepository.count()).isZero();
        assertThat(pointsRepository.count()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayPublishesTheStoredPayloadAndDeletesTheRow() throws Exception {
        when(rocketMQTemplate.syncSend(anyString(), anyCollection())).thenReturn(sendOk());
        addPoints("user_1", 100, "req-1");
        String payload = outboxRows().getFirst().getPayload();

        outboxRelay.publishPending();

        ArgumentCaptor<Collection<Message<?>>> batch = ArgumentCaptor.captor();
        verify(rocketMQTemplate).syncSend(eq("user-points-topic:ADDED"), batch.capture());
        assertThat(batch.getValue()).extracting("payload").containsExactly(payload);
        assertThat(outboxRepository.count()).isZero();
    }

    private ResultActions addPoints(String userId, long amount, String requestId) throws Exception {
        return mockMvc.perform(post("/points")
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"userId":"%s","amount":%d,"reason":"test"}""".formatted(userId, amount)));
    }

    private List<OutboxMessage> outboxRows() {
        return outboxRepository.findAll(Sort.by("id"));
    }

    private static SendResult sendOk() {
        var result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        return result;
    }
}
