package com.sahmhoot.chat.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "room_id", nullable = false)
  private Long roomId;
  @Column(name = "sender_participant_id", nullable = false)
  private Long senderParticipantId;
  @Column(nullable = false, length = 300)
  private String content;
  @Column(name = "parent_id")
  private Long parentId;
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
  @Column(name = "deleted_at")
  private Instant deletedAt;

  public ChatMessage(Long roomId, Long senderParticipantId,
      String content, Long parentId, Instant createdAt) {
    this.roomId = roomId;
    this.senderParticipantId = senderParticipantId;
    this.content = content;
    this.parentId = parentId;
    this.createdAt = createdAt;
  }
}
