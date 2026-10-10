package com.sahmhoot.quiz.entity;

/**
 * 실행 문항(run_questions) 상태.
 * - READY: 출제 대기
 * - OPEN: 문항 공개 및 응답 접수 중
 * - CLOSED: 문항 마감
 */
public enum RunQuestionStatus {
  READY,
  OPEN,
  CLOSED
}
