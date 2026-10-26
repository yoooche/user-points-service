package com.yoooche.points.controller;

import com.yoooche.points.constant.Validation;
import com.yoooche.points.constant.Headers;
import com.yoooche.points.dto.AddPointsRequest;
import com.yoooche.points.dto.PointRecordResponse;
import com.yoooche.points.dto.UpdateReasonRequest;
import com.yoooche.points.dto.UserPointsResponse;
import com.yoooche.points.exception.ErrorResponse;
import com.yoooche.points.service.UserPointsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "User Points Service")
@RestController
@RequestMapping(value = "/points", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class UserPointsController {

    private final UserPointsService userPointsService;

    @Operation(summary = "Add points to a user",
            description = "Stores a point record, adds the amount to the user's total ")
    @ApiResponse(responseCode = "201", description = "Record created")
    @ApiResponse(responseCode = "400", description = "Missing/invalid Idempotency-Key or invalid request body",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Idempotency-Key was used with a different request")
    @ApiResponse(responseCode = "503", description = "Database busy; retry with the same key")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PointRecordResponse addPoints(@Valid @RequestBody AddPointsRequest request,
            @Parameter(description = "Keys are shared across POST /points and retained until the point record is deleted.")
            @RequestHeader(Headers.IDEMPOTENCY_KEY)
            @Pattern(regexp = "[A-Za-z0-9_-]{1,128}", message = "must contain 1–128 letters, digits, '_' or '-'") String idempotencyKey) {
        return userPointsService.addPoints(request, idempotencyKey);
    }

    @Operation(
            summary = "Get the top 10 users by total points",
            description = "Returns up to 10 users sorted by total points in descending order. "
                    + "Users with the same total points are sorted by user ID in ascending order.")
    @ApiResponse(responseCode = "200", description = "Up to 10 entries; empty when no user has points")
    @GetMapping("/leaderboard")
    public List<UserPointsResponse> getLeaderboard() {
        return userPointsService.getLeaderboard();
    }

    @Operation(summary = "Get a user's total points")
    @ApiResponse(responseCode = "200", description = "Current total")
    @ApiResponse(responseCode = "404", description = "The user has no points (`user_not_found`)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{userId}")
    public UserPointsResponse getTotalUserPoints(
            @Parameter(description = "User id. `leaderboard` is reserved by the leaderboard endpoint.", example = "user_123")
            @PathVariable @Pattern(regexp = Validation.USER_ID_PATTERN, message = Validation.USER_ID_MESSAGE) String userId) {
        return userPointsService.getTotalUserPoints(userId);
    }

    @Operation(summary = "Update the reason of a point record",
            description = "Only the reason changes; the amount and the user's total are unaffected.")
    @ApiResponse(responseCode = "200", description = "Updated record")
    @ApiResponse(responseCode = "404", description = "No record with this id (`record_not_found`)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public PointRecordResponse updateReason(
            @Parameter(description = "Point record id returned by `POST /points`. This is not a user id.", example = "1")
            @PathVariable Long id,
            @Valid @RequestBody UpdateReasonRequest request) {
        return userPointsService.updateReason(id, request.reason());
    }

    @Operation(summary = "Delete all points of a user",
            description = "Removes every point record and the total of the user. No event is published.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404",
            description = "Nothing to delete (`user_not_found`). When retrying a DELETE, treat this as success.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUserPoints(
            @Parameter(description = "User id", example = "user_123")
            @PathVariable @Pattern(regexp = Validation.USER_ID_PATTERN, message = Validation.USER_ID_MESSAGE) String userId) {
        userPointsService.deleteUserPoints(userId);
    }
}
