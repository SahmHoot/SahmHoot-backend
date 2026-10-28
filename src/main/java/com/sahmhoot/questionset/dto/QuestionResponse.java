package com.sahmhoot.questionset.dto;

import com.sahmhoot.questionset.entity.Question;
import com.sahmhoot.questionset.entity.QuestionType;
import java.util.List;

public record QuestionResponse(Long id, int orderNo, QuestionType type, String content,
    int timeLimitSeconds, List<ChoiceResponse> choices) {
  public static QuestionResponse from(Question q, List<ChoiceResponse> choices) {
    return new QuestionResponse(q.getId(), q.getOrderNo(), q.getType(), q.getContent(),
        q.getTimeLimitSeconds(), choices);
  }
}
