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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 실행 선택지 엔티티 (run_choices 테이블 매핑).
 * 퀴즈 시작 시 원본 선택지를 복사한 실행용 스냅샷이다.
 */
@Entity
@Table(name = "run_choices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class RunChoice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "run_question_id", nullable = false)
  private RunQuestion runQuestion;

  @Column(name = "content", length = 500, nullable = false)
  private String content;

  @Column(name = "order_no", nullable = false)
  private int orderNo;

  @Column(name = "is_correct", nullable = false)
  private boolean correct;
}
