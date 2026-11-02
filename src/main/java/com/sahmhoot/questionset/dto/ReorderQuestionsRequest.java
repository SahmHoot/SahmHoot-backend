package com.sahmhoot.questionset.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record ReorderQuestionsRequest(@NotNull List<@NotNull @Positive Long> questionIds) {}
