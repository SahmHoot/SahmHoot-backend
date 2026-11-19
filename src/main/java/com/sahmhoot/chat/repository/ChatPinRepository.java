package com.sahmhoot.chat.repository;

import com.sahmhoot.chat.entity.ChatPin;
import com.sahmhoot.chat.entity.ChatPinId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ChatPinRepository extends JpaRepository<ChatPin, ChatPinId> {
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ChatPin p WHERE p.id.roomId = :roomId AND p.id.messageId = :messageId")
  int removePin(Long roomId, Long messageId);
}
