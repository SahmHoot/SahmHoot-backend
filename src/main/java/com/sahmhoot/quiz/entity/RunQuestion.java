package com.sahmhoot.quiz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 실행 문항 엔티티 (run_questions 테이블 매핑).
 * 퀴즈 시작 시 문제 세트의 원본 문항을 스냅샷으로 복사하여 관리한다.
 */
@Entity
@Table(name = "run_questions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class RunQuestion {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "quiz_run_id", nullable = false)
  private QuizRun quizRun;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false)
  private QuestionType type;

  @Column(name = "content", columnDefinition = "TEXT", nullable = false)
  private String content;

  @Column(name = "time_limit_seconds", nullable = false)
  private int timeLimitSeconds;

  @Column(name = "order_no", nullable = false)
  private int orderNo;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private RunQuestionStatus status;

  @Column(name = "opened_at")
  private Instant openedAt;

  @Column(name = "closes_at")
  private Instant closesAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  public void open(Instant openedAt, Instant closesAt) {
    this.status = RunQuestionStatus.OPEN;
    this.openedAt = openedAt;
    this.closesAt = closesAt;
  }

  public void close(Instant closedAt) {
    this.status = RunQuestionStatus.CLOSED;
    this.closedAt = closedAt;
  }

  public boolean isOpen() {
    return this.status == RunQuestionStatus.OPEN;
  }

  public boolean isClosed() {
    return this.status == RunQuestionStatus.CLOSED;
  }

  public boolean isReady() {
    return this.status == RunQuestionStatus.READY;
  }
}
