package com.sahmhoot.quiz.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 06 실시간 규격 2절 참가자 답변 제출 DTO (/app/rooms/{roomId}/answer).
 */
public record SubmitAnswerRequest(
    @NotNull(message = "실행 문항 ID는 필수입니다.")
    Long runQuestionId,

    @NotNull(message = "선택지 ID는 필수입니다.")
    Long choiceId
) {}
