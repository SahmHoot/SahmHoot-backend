package com.sahmhoot.quiz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 퀴즈 실행 엔티티 (quiz_runs 테이블 매핑).
 * 특정 수업 방에서 실행된 하나의 퀴즈 회차 정보를 나타낸다.
 */
@Entity
@Table(name = "quiz_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class QuizRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "room_id", nullable = false)
  private Long roomId;

  @Column(name = "question_set_id", nullable = false)
  private Long questionSetId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private QuizRunStatus status;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  public void start(Instant now) {
    this.status = QuizRunStatus.RUNNING;
    this.startedAt = now;
  }

  public void finish(Instant now) {
    this.status = QuizRunStatus.FINISHED;
    this.finishedAt = now;
  }

  public void abort(Instant now) {
    this.status = QuizRunStatus.ABORTED;
    this.finishedAt = now;
  }

  public boolean isRunning() {
    return this.status == QuizRunStatus.RUNNING;
  }

  public boolean isFinished() {
    return this.status == QuizRunStatus.FINISHED;
  }

  public boolean isAborted() {
    return this.status == QuizRunStatus.ABORTED;
  }
}
