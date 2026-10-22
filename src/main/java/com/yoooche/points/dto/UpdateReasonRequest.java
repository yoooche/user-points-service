package com.yoooche.points.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateReasonRequest(@NotBlank @Size(max = 255) String reason) {

    public UpdateReasonRequest {
        reason = reason == null ? null : reason.strip();
    }
}
