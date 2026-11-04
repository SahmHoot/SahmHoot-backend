package com.sahmhoot.questionset.dto;

import com.sahmhoot.questionset.entity.QuestionType;
import com.sahmhoot.questionset.validation.ValidQuestionChoices;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

@ValidQuestionChoices
public record QuestionWriteRequest(@NotNull QuestionType type,
    @NotBlank @Size(max = 500) String content,
    @Min(10) @Max(300) Integer timeLimitSeconds,
    @NotNull List<@NotNull @Valid ChoiceRequest> choices) {
  public int secondsOrDefault() { return timeLimitSeconds == null ? 60 : timeLimitSeconds; }
}
