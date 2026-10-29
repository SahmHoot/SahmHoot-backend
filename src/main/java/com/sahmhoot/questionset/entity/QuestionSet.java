package com.sahmhoot.questionset.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "question_sets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionSet {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "host_id", nullable = false)
  private Long hostId;
  @Column(nullable = false, length = 255)
  private String title;
  @Column(columnDefinition = "TEXT")
  private String description;
  @Enumerated(EnumType.STRING) @Column(nullable = false)
  private Visibility visibility;
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public QuestionSet(Long hostId, String title, String description, Visibility visibility) {
    this.hostId = hostId;
    this.title = title;
    this.description = description;
    this.visibility = visibility;
    this.createdAt = Instant.now();
    this.updatedAt = createdAt;
  }

  public void update(String title, String description, Visibility visibility) {
    this.title = title;
    this.description = description;
    this.visibility = visibility;
    touch();
  }

  public void touch() { updatedAt = Instant.now(); }
}
