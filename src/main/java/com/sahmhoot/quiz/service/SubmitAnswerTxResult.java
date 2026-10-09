package com.sahmhoot.quiz.service;

/**
 * 답변 제출 트랜잭션 실행 결과 레코드.
 */
public record SubmitAnswerTxResult(
    Long runQuestionId,
    int answeredCount,
    boolean shouldClose
) {}
