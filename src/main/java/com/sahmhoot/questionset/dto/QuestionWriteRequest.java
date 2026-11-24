package com.sahmhoot.questionset.dto;

import com.sahmhoot.questionset.entity.QuestionType;
import com.sahmhoot.questionset.validation.ValidQuestionChoices;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import tools.jackson.databind.JsonNode;

@ValidQuestionChoices
public record QuestionWriteRequest(@NotNull QuestionType type,
    @NotBlank @Size(max = 500) String content,
    @Min(10) @Max(300) Integer timeLimitSeconds,
    @NotNull List<@NotNull @Valid ChoiceRequest> choices, JsonNode imageRef) {
  public QuestionWriteRequest(QuestionType type, String content, Integer timeLimitSeconds, List<ChoiceRequest> choices) {
    this(type, content, timeLimitSeconds, choices, null);
  }
  public int secondsOrDefault() { return timeLimitSeconds == null ? 60 : timeLimitSeconds; }
}
