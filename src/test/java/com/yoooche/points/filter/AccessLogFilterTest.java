package com.yoooche.points.filter;

import com.yoooche.points.controller.UserPointsController;
import com.yoooche.points.service.UserPointsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@WebMvcTest(UserPointsController.class)
@ExtendWith(OutputCaptureExtension.class)
class AccessLogFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserPointsService service;

    @Test
    void logsMethodPathStatusAndRequestId(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/points/leaderboard").header(RequestIdFilter.HEADER, "trace-7"));

        assertThat(output).containsPattern("\\[trace-7] .*GET /points/leaderboard -> 200 \\(\\d+ ms\\)");
    }

    @Test
    void neverLogsRequestBody(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/points").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"userId":"secret_user","amount":-1,"reason":"secret_reason"}"""));

        assertThat(output).contains("POST /points -> 400").doesNotContain("secret_reason");
    }
}
