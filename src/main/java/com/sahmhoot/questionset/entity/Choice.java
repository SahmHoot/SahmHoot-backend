package com.sahmhoot.questionset.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "choices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Choice {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "question_id", nullable = false)
  private Question question;
  @Column(nullable = false, length = 500)
  private String content;
  @Column(name = "order_no", nullable = false)
  private int orderNo;
  @Column(name = "is_correct", nullable = false)
  private boolean correct;

  public Choice(Question question, String content, int orderNo, boolean correct) {
    this.question = question;
    this.content = content;
    this.orderNo = orderNo;
    this.correct = correct;
  }
}
