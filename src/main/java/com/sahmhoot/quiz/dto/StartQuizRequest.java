package com.sahmhoot.quiz.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 05 API 명세 23번 퀴즈 시작 요청 DTO.
 */
public record StartQuizRequest(
    @NotNull(message = "문제 세트 ID는 필수입니다.")
    Long questionSetId
) {}
