package com.sahmhoot.quiz.entity;

/**
 * 퀴즈 실행(quiz_runs) 상태.
 * - READY: 준비 완료 (스냅샷 복사 완료)
 * - RUNNING: 퀴즈 진행 중
 * - FINISHED: 퀴즈 정상 종료
 * - ABORTED: 교수자에 의한 퀴즈 중단
 */
public enum QuizRunStatus {
  READY,
  RUNNING,
  FINISHED,
  ABORTED
}
