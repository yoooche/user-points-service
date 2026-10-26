package com.yoooche.points;

import com.yoooche.points.dto.AddPointsRequest;
import com.yoooche.points.constant.Headers;
import com.yoooche.points.repository.OutboxMessageRepository;
import com.yoooche.points.repository.UserPointsRecordRepository;
import com.yoooche.points.repository.UserPointsRepository;
import com.yoooche.points.service.UserPointsService;
import com.yoooche.points.utils.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestDataCleaner.class})
class IdempotencyIntegrationTest {

    private static final String BODY = """
            {"userId":"retry_user","amount":100,"reason":"bonus"}""";

    @Autowired private MockMvc mvc;
    @Autowired private TestDataCleaner cleaner;
    @Autowired private TestTransactionFailure transactionFailure;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserPointsRepository totals;
    @Autowired private UserPointsRecordRepository records;
    @Autowired private OutboxMessageRepository outbox;
    @Autowired private UserPointsService pointsService;
    @Autowired private TransactionTemplate transaction;

    @BeforeEach
    void resetData() {
        cleaner.reset();
    }

    @Test
    void missingKeyIsRejectedWithoutAddingOrPublishing() throws Exception {
        mvc.perform(post("/points").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value("invalid_parameter"))
                .andExpect(jsonPath("$.message").value("Missing required header 'Idempotency-Key'"));
        assertThat(records.count()).isZero();
        assertThat(totals.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void lostResponseCanBeReplayedWithoutAddingOrPublishingAgain() throws Exception {
        String original = add("retry-1", BODY).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String replay = add("retry-1", BODY).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(original);
        assertOneAdd();
    }

    @Test
    void normalizedBodyAndPropertyOrderDoNotChangeTheOperation() throws Exception {
        String original = add("retry-1", BODY).andReturn().getResponse().getContentAsString();
        String replay = add("retry-1", """
                {"reason":"  bonus  ","amount":100,"userId":"retry_user"}""")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(original);
        assertOneAdd();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"userId\":\"other_user\",\"amount\":100,\"reason\":\"bonus\"}",
            "{\"userId\":\"retry_user\",\"amount\":101,\"reason\":\"bonus\"}",
            "{\"userId\":\"retry_user\",\"amount\":100,\"reason\":\"other\"}"
    })
    void sameKeyWithDifferentPayloadReturns409(String body) throws Exception {
        add("retry-1", BODY).andExpect(status().isCreated());
        add("retry-1", body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.statusCode").value("idempotency_conflict"));
        assertOneAdd();
    }

    @Test
    void differentKeysIncludingCaseDifferencesAreIndependentAdds() throws Exception {
        add("Retry-1", BODY).andExpect(status().isCreated());
        add("retry-1", BODY).andExpect(status().isCreated());
        assertThat(totals.findTotalByUserId("retry_user")).isEqualTo(200);
        assertThat(records.count()).isEqualTo(2);
        assertThat(outbox.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad key", "中文", "a/b"})
    void invalidKeyDoesNotReserveOrAdd(String key) throws Exception {
        add(key, BODY).andExpect(status().isBadRequest());
        assertThat(requestCount()).isZero();
        assertThat(records.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void keyLengthIsBounded() throws Exception {
        add("a".repeat(129), BODY).andExpect(status().isBadRequest());
        add("a".repeat(128), BODY).andExpect(status().isCreated());
        assertOneAdd();
    }

    @Test
    void invalidBodyDoesNotReserveTheKey() throws Exception {
        add("retry-1", BODY.replace("100", "-1")).andExpect(status().isBadRequest());
        assertThat(requestCount()).isZero();
        add("retry-1", BODY).andExpect(status().isCreated());
        assertOneAdd();
    }

    @Test
    void concurrentRetriesProduceOneRecordAndOneEvent() throws Exception {
        try (var executor = Executors.newFixedThreadPool(12)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < 12; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return add("concurrent-1", BODY).andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString();
                }));
            }
            start.countDown();
            String original = results.getFirst().get(15, TimeUnit.SECONDS);
            for (var result : results) {
                assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo(original);
            }
        }
        assertOneAdd();
    }

    @Test
    void concurrentDifferentPayloadsAllowOnlyOneWinner() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return add("race-1", BODY).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await();
                return add("race-1", BODY.replace("100", "200")).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(records.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(totals.findTotalByUserId("retry_user"))
                .isEqualTo(records.findAll().getFirst().getAmount());
    }

    @Test
    void rollbackRemovesKeyPointsAndOutboxSoRetryCanExecute() throws Exception {
        assertThatThrownBy(() -> transactionFailure.runWithRollback(
                () -> pointsService.addPoints(new AddPointsRequest("retry_user", 100L, "bonus"), "retry-1"),
                () -> {
                    assertThat(requestCount()).isEqualTo(1);
                    assertThat(outbox.count()).isEqualTo(1);
                })).hasMessage("force rollback");
        assertThat(requestCount()).isZero();
        assertThat(records.count()).isZero();
        assertThat(totals.count()).isZero();
        assertThat(outbox.count()).isZero();

        add("retry-1", BODY).andExpect(status().isCreated());
        assertOneAdd();
    }

    @Test
    void businessFailureDoesNotReserveTheKey() throws Exception {
        transaction.executeWithoutResult(tx -> totals.addToTotal("retry_user", Long.MAX_VALUE));
        add("retry-1", BODY).andExpect(status().isUnprocessableEntity());
        assertThat(requestCount()).isZero();
        assertThat(records.count()).isZero();
        assertThat(outbox.count()).isZero();
        pointsService.deleteUserPoints("retry_user");

        add("retry-1", BODY).andExpect(status().isCreated());
        assertOneAdd();
    }

    @Test
    void replaySurvivesReasonUpdateButDeletionReleasesTheKey() throws Exception {
        String original = add("retry-1", BODY).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = JsonUtils.readTree(original).get("id").asLong();
        pointsService.updateReason(id, "edited");
        assertThat(add("retry-1", BODY).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).isEqualTo(original);
        assertThat(records.findById(id).orElseThrow().getReason()).isEqualTo("edited");
        pointsService.deleteUserPoints("retry_user");

        assertThat(requestCount()).isZero();
        String afterDelete = add("retry-1", BODY).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonUtils.readTree(afterDelete).get("id").asLong()).isNotEqualTo(id);
        assertThat(records.count()).isEqualTo(1);
        assertThat(totals.findTotalByUserId("retry_user")).isEqualTo(100);
        assertThat(outbox.count()).isEqualTo(2);
        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void lockTimeoutReturns503AndDoesNotExecuteAnotherAdd() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var heldTransaction = new TransactionTemplate(transaction.getTransactionManager());
            heldTransaction.setTimeout(20);
            heldTransaction.executeWithoutResult(tx -> {
                // Hold an uncommitted key as if the first writer were still working; roll this fixture back below.
                var pending = new com.yoooche.points.model.UserPointsRecord();
                pending.setUserId("retry_user");
                pending.setAmount(100);
                pending.setReason("bonus");
                pending.setIdempotencyKey("locked-key");
                records.saveAndFlush(pending);
                var blocked = executor.submit(() -> add("locked-key", BODY).andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.statusCode").value("request_busy")));
                try {
                    blocked.get(15, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                } finally {
                    tx.setRollbackOnly();
                }
            });
        }
        assertThat(requestCount()).isZero();
        assertThat(records.count()).isZero();
        assertThat(totals.count()).isZero();
        assertThat(outbox.count()).isZero();
        add("locked-key", BODY).andExpect(status().isCreated());
        assertOneAdd();
    }

    private ResultActions add(String key, String body) throws Exception {
        return mvc.perform(post("/points").header(Headers.IDEMPOTENCY_KEY, key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long requestCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_points_records WHERE idempotency_key IS NOT NULL", Long.class);
    }

    private void assertOneAdd() {
        assertThat(totals.findTotalByUserId("retry_user")).isEqualTo(100);
        assertThat(records.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(requestCount()).isEqualTo(1);
    }
}
