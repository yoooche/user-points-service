package com.yoooche.points.filter;

import com.yoooche.points.controller.PointsController;
import com.yoooche.points.service.PointsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(PointsController.class)
class RequestIdFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PointsService service;

    @Test
    void generatesIdWhenClientSendsNone() throws Exception {
        MvcResult result = mockMvc.perform(get("/points/leaderboard")).andReturn();

        assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER))
                .matches("[0-9a-f-]{36}");
    }

    @Test
    void reusesValidClientId() throws Exception {
        mockMvc.perform(get("/points/leaderboard").header(RequestIdFilter.HEADER, "abc-123"))
                .andExpect(header().string(RequestIdFilter.HEADER, "abc-123"));
    }

    @Test
    void replacesIdThatCouldForgeLogLines() throws Exception {
        MvcResult result = mockMvc.perform(get("/points/leaderboard")
                        .header(RequestIdFilter.HEADER, "x\nINFO fake log line"))
                .andReturn();

        assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER)).doesNotContain("fake");
    }

    @Test
    void errorResponseCarriesTheSameId() throws Exception {
        mockMvc.perform(get("/points/a-very-long-user-id-that-exceeds-the-sixty-four-character-limit-for-ids")
                        .header(RequestIdFilter.HEADER, "trace-42"))
                .andExpect(header().string(RequestIdFilter.HEADER, "trace-42"))
                .andExpect(jsonPath("$.requestId").value("trace-42"));
    }
}
