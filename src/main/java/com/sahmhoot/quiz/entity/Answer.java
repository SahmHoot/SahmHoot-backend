package com.sahmhoot.quiz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 답변 엔티티 (answers 테이블 매핑).
 * 참가자가 실행 문항에 제출한 최종 답변을 관리한다.
 * 마감 전까지 변경 가능하며(1인 1응답), 변경 시 run_choice_id 및 updated_at을 갱신한다.
 */
@Entity
@Table(name = "answers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Answer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "run_question_id", nullable = false)
  private RunQuestion runQuestion;

  @Column(name = "participant_id", nullable = false)
  private Long participantId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "run_choice_id", nullable = false)
  private RunChoice runChoice;

  @Column(name = "submitted_at", nullable = false)
  private Instant submittedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public void updateChoice(RunChoice newChoice, Instant now) {
    this.runChoice = newChoice;
    this.updatedAt = now;
  }
}
