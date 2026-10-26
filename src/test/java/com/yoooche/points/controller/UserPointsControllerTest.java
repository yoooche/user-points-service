package com.yoooche.points.controller;

import com.yoooche.points.constant.Headers;

import com.yoooche.points.dto.PointRecordResponse;
import com.yoooche.points.exception.BaseException;
import com.yoooche.points.constant.StatusCode;
import com.yoooche.points.service.UserPointsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserPointsController.class)
class UserPointsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserPointsService service;

    @Test
    void addPointsReturns201() throws Exception {
        when(service.addPoints(any(), any(String.class))).thenReturn(
                new PointRecordResponse(1L, "user_123", 100, "signup_bonus", Instant.now()));

        mockMvc.perform(post("/points").header(Headers.IDEMPOTENCY_KEY, java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user_123","amount":100,"reason":"signup_bonus"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void addPointsRejectsInvalidBodyWithFieldErrors() throws Exception {
        mockMvc.perform(post("/points").header(Headers.IDEMPOTENCY_KEY, java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"","amount":-5}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value("invalid_parameter"))
                .andExpect(jsonPath("$.errors.userId").exists())
                .andExpect(jsonPath("$.errors.amount").exists())
                .andExpect(jsonPath("$.errors.reason").exists());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/points").header(Headers.IDEMPOTENCY_KEY, java.util.UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value("invalid_parameter"));
    }

    @Test
    void unknownUserReturns404() throws Exception {
        when(service.getTotalUserPoints("ghost")).thenThrow(new BaseException(StatusCode.USER_NOT_FOUND, "User not found: ghost"));

        mockMvc.perform(get("/points/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.system").value("user-points-service"))
                .andExpect(jsonPath("$.statusCode").value("user_not_found"))
                .andExpect(jsonPath("$.message").value("User not found: ghost"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void leaderboardIsNotTreatedAsUserId() throws Exception {
        mockMvc.perform(get("/points/leaderboard")).andExpect(status().isOk());
    }

    @Test
    void nonNumericRecordIdReturns400() throws Exception {
        mockMvc.perform(put("/points/abc").contentType(MediaType.APPLICATION_JSON).content("""
                        {"reason":"x"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMissingRecordReturns404() throws Exception {
        when(service.updateReason(eq(99L), any())).thenThrow(new BaseException(StatusCode.RECORD_NOT_FOUND, "Point record not found: 99"));

        mockMvc.perform(put("/points/99").contentType(MediaType.APPLICATION_JSON).content("""
                        {"reason":"x"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/points/user_123")).andExpect(status().isNoContent());
    }

    @Test
    void deleteUnknownUserReturns404() throws Exception {
        doThrow(new BaseException(StatusCode.USER_NOT_FOUND, "User not found: ghost")).when(service).deleteUserPoints("ghost");

        mockMvc.perform(delete("/points/ghost")).andExpect(status().isNotFound());
    }

    @Test
    void unsupportedMethodUsesSameErrorFormat() throws Exception {
        mockMvc.perform(post("/points/leaderboard"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.statusCode").value("method_not_allowed"));
    }

    @Test
    void unexpectedErrorHidesInternalMessage() throws Exception {
        when(service.getLeaderboard()).thenThrow(new IllegalStateException("db password is wrong"));

        mockMvc.perform(get("/points/leaderboard"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.statusCode").value("unknown_err"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }
}
