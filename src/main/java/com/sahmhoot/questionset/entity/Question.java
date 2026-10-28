package com.sahmhoot.questionset.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "questions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Question {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "question_set_id", nullable = false)
  private QuestionSet questionSet;
  @Enumerated(EnumType.STRING) @Column(nullable = false)
  private QuestionType type;
  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;
  @Column(name = "time_limit_seconds", nullable = false)
  private int timeLimitSeconds;
  @Column(name = "order_no", nullable = false)
  private int orderNo;
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public Question(QuestionSet set, QuestionType type, String content, int seconds, int orderNo) {
    this.questionSet = set;
    this.orderNo = orderNo;
    this.createdAt = Instant.now();
    update(type, content, seconds);
  }

  public void update(QuestionType type, String content, int seconds) {
    this.type = type;
    this.content = content;
    this.timeLimitSeconds = seconds;
    this.updatedAt = Instant.now();
  }

  public void reorder(int orderNo) {
    this.orderNo = orderNo;
    this.updatedAt = Instant.now();
  }
}
