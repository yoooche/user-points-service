package com.yoooche.points.dto;

import com.yoooche.points.constant.Validation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.Range;

public record AddPointsRequest(
        @Schema(description = "1-64 characters of letters, digits, '_' or '-'. Case-sensitive.", example = "user_123")
        @NotNull @Pattern(regexp = Validation.USER_ID_PATTERN, message = Validation.USER_ID_MESSAGE) String userId,

        @Schema(description = "1 to 1,000,000. Whole numbers only; deductions are not supported.",
                example = "100")
        @NotNull @Range(min = 1, max = Validation.MAX_AMOUNT) Long amount,

        @Schema(example = "signup_bonus")
        @NotBlank @Size(max = 255) String reason) {

    public AddPointsRequest {
        reason = reason == null ? null : reason.strip();
    }
}
