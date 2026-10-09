package com.sahmhoot.quiz.dto;

import java.time.Instant;

/**
 * 05 API 명세 23번 퀴즈 시작 응답 DTO.
 */
public record StartQuizResponse(
    Long runId,
    int totalQuestions,
    Long firstRunQuestionId,
    Instant closesAt
) {}
