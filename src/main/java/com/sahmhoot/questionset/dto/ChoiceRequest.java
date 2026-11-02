package com.sahmhoot.questionset.dto;

import jakarta.validation.constraints.*;

public record ChoiceRequest(@NotBlank @Size(max = 200) String content,
    @NotNull Boolean isCorrect) {}
