package com.sahmhoot.questionset.dto;

import com.sahmhoot.questionset.entity.Visibility;
import jakarta.validation.constraints.*;

public record CreateQuestionSetRequest(@NotBlank @Size(max = 50) String title,
    String description, Visibility visibility) {}
