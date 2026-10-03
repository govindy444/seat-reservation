package com.example.seats.show;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateShowRequest(
        @NotBlank @Size(max = 200) String name,
        @NotEmpty @Size(max = 20_000) List<@NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,16}") String> seats,
        @NotNull @PositiveOrZero Long pricePaise,
        @Min(1) @Max(100) Integer perUserLimit) {

    public static final int DEFAULT_PER_USER_LIMIT = 4;

    public int perUserLimitOrDefault() {
        return perUserLimit == null ? DEFAULT_PER_USER_LIMIT : perUserLimit;
    }
}
