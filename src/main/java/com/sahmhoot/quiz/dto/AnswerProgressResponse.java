package com.sahmhoot.quiz.dto;

/**
 * 06 실시간 규격 ANSWER_PROGRESS DTO.
 * 진행 중 교수 화면의 응답 인원 표시에 사용된다.
 */
public record AnswerProgressResponse(
    Long runQuestionId,
    int answeredCount
) {}
