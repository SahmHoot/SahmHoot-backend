package com.sahmhoot.questionset.dto;

import com.sahmhoot.questionset.entity.Choice;

public record ChoiceResponse(Long id, int orderNo, String content, boolean isCorrect) {
  public static ChoiceResponse from(Choice choice) {
    return new ChoiceResponse(choice.getId(), choice.getOrderNo(), choice.getContent(),
        choice.isCorrect());
  }
}
