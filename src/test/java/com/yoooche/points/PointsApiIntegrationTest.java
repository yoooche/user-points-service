package com.yoooche.points;

import com.yoooche.points.constant.Headers;

import com.yoooche.points.repository.UserPointsRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestDataCleaner.class})
@ExtendWith(OutputCaptureExtension.class)
class PointsApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserPointsRecordRepository recordRepository;

    @Autowired
    private TestDataCleaner testDataCleaner;

    @BeforeEach
    void resetData() {
        testDataCleaner.reset();
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        addPoints("""
                {"userId":"user_001","amount":950,"reason":"signup_bonus"}""").andExpect(status().isCreated());
        addPoints("""
                {"userId":"user_002","amount":820,"reason":"signup_bonus"}""").andExpect(status().isCreated());
        String body = addPoints("""
                {"userId":"user_001","amount":50,"reason":"daily"}""")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long recordId = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(get("/points/user_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1000));
        mockMvc.perform(get("/points/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value("user_001"))
                .andExpect(jsonPath("$[1].userId").value("user_002"));
        mockMvc.perform(put("/points/{id}", recordId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"weekly"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("weekly"))
                .andExpect(jsonPath("$.amount").value(50));
        mockMvc.perform(delete("/points/user_001")).andExpect(status().isNoContent());
        mockMvc.perform(get("/points/user_001")).andExpect(status().isNotFound());
    }

    @Test
    void userIdsDifferingOnlyInCaseAreDifferentUsers() throws Exception {
        addPoints("""
                {"userId":"caseUser","amount":5,"reason":"a"}""").andExpect(status().isCreated());
        addPoints("""
                {"userId":"CASEUSER","amount":7,"reason":"b"}""").andExpect(status().isCreated());

        mockMvc.perform(get("/points/caseUser")).andExpect(jsonPath("$.total").value(5));
        mockMvc.perform(get("/points/CASEUSER")).andExpect(jsonPath("$.total").value(7));

        mockMvc.perform(delete("/points/CASEUSER")).andExpect(status().isNoContent());
        mockMvc.perform(get("/points/caseUser")).andExpect(jsonPath("$.total").value(5));
        assertThat(recordRepository.count()).isEqualTo(1);
    }

    @Test
    void amountAboveLimitIsRejected() throws Exception {
        addPoints("""
                {"userId":"u1","amount":1000001,"reason":"a"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void differentKeysAddTwiceEvenWithTheSameRequestId() throws Exception {
        String body = """
                {"userId":"retry_user","amount":100,"reason":"signup_bonus"}""";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/points").header(Headers.IDEMPOTENCY_KEY, java.util.UUID.randomUUID().toString()).header("X-Request-Id", "same-request")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/points/retry_user"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(200));
        assertThat(recordRepository.count()).isEqualTo(2);
    }

    @Test
    void decimalAmountIsRejectedInsteadOfTruncated() throws Exception {
        addPoints("""
                {"userId":"u1","amount":1.9,"reason":"a"}""").andExpect(status().isBadRequest());
        assertThat(recordRepository.count()).isZero();
    }

    @Test
    void stringAmountIsRejected() throws Exception {
        addPoints("""
                {"userId":"u1","amount":"3","reason":"a"}""").andExpect(status().isBadRequest());
    }

    @Test
    void userIdWithSurroundingWhitespaceIsRejected() throws Exception {
        addPoints("""
                {"userId":"u1 ","amount":1,"reason":"a"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.userId").exists());
    }

    @Test
    void pathUserIdIsValidatedWithFieldErrors(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/points/{userId}", "abc\nFAKE ERROR line"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value("invalid_parameter"))
                .andExpect(jsonPath("$.errors.userId").exists());

        assertThat(output.getAll().lines()).noneMatch(line -> line.startsWith("FAKE ERROR"));
    }

    @Test
    void clientErrorsDoNotExposeInternals() throws Exception {
        expectNoInternals(mockMvc.perform(post("/points").header(Headers.IDEMPOTENCY_KEY, java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)));
        expectNoInternals(addPoints("""
                {"userId":"u1","amount":"abc","reason":"a"}"""));
        expectNoInternals(mockMvc.perform(put("/points/abc").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason":"x"}""")));
    }

    @Test
    void reasonIsStrippedButUserIdIsNot() throws Exception {
        addPoints("""
                {"userId":"u1","amount":1,"reason":"\u3000 signup_bonus  "}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reason").value("signup_bonus"));
        addPoints("""
                {"userId":"u1","amount":1,"reason":"   "}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
    }

    private ResultActions addPoints(String json) throws Exception {
        return mockMvc.perform(post("/points").header(Headers.IDEMPOTENCY_KEY, java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static void expectNoInternals(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("java."))))
                .andExpect(content().string(not(containsString("com.yoooche"))));
    }
}
