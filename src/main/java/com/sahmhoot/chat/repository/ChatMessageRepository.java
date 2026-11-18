package com.sahmhoot.chat.repository;

import com.sahmhoot.chat.entity.ChatMessage;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
  Optional<ChatMessage> findByIdAndRoomId(Long id, Long roomId);
  Optional<ChatMessage> findByIdAndRoomIdAndDeletedAtIsNull(Long id, Long roomId);
  List<ChatMessage> findTop50ByRoomIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(Long roomId);
  List<ChatMessage> findByRoomIdAndIdIn(Long roomId, Set<Long> ids);

  @Query("""
      SELECT m FROM ChatMessage m, ChatPin p
      WHERE p.id.roomId = :roomId AND m.roomId = p.id.roomId
        AND m.id = p.id.messageId AND m.deletedAt IS NULL
      ORDER BY p.pinnedAt, m.id
      """)
  List<ChatMessage> findPinnedMessages(Long roomId);
}
