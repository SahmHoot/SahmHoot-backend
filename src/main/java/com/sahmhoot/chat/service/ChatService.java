package com.sahmhoot.chat.service;

import com.sahmhoot.chat.dto.ChatMessageResponse;
import com.sahmhoot.chat.dto.SendChatRequest;
import com.sahmhoot.chat.entity.ChatMessage;
import com.sahmhoot.chat.entity.ChatRestrictionId;
import com.sahmhoot.chat.event.ChatMessageEvent;
import com.sahmhoot.chat.exception.ChatErrorCode;
import com.sahmhoot.chat.exception.ChatException;
import com.sahmhoot.chat.repository.ChatMessageRepository;
import com.sahmhoot.chat.repository.ChatRestrictionRepository;
import com.sahmhoot.chat.service.port.ChatRoomPort;
import com.sahmhoot.quiz.service.port.RoomLockPort;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatService {
  private final RoomLockPort locks;
  private final ChatRoomPort rooms;
  private final ChatMessageRepository messages;
  private final ChatRestrictionRepository restrictions;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final TransactionTemplate writing;

  public ChatService(RoomLockPort locks, ChatRoomPort rooms, ChatMessageRepository messages,
      ChatRestrictionRepository restrictions, ApplicationEventPublisher events, Clock clock,
      PlatformTransactionManager transactions) {
    this.locks = locks;
    this.rooms = rooms;
    this.messages = messages;
    this.restrictions = restrictions;
    this.events = events;
    this.clock = clock;
    this.writing = new TransactionTemplate(transactions);
  }

  public void send(Long roomId, Long userId, SendChatRequest request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("ChatService must be called before starting a transaction");
    }
    // 수업 종료와 채팅 저장·커밋 후 발행을 같은 방 잠금으로 직렬화한다.
    locks.executeWithLock(roomId, () -> writing.executeWithoutResult(tx -> sendInTransaction(roomId, userId, request)));
  }

  private void sendInTransaction(Long roomId, Long userId, SendChatRequest request) {
    var room = rooms.findRoom(roomId).orElseThrow(() -> new ChatException(ChatErrorCode.ROOM_NOT_FOUND));
    if (room.closed()) throw new ChatException(ChatErrorCode.ROOM_CLOSED);
    var sender = rooms.findActiveParticipant(roomId, userId)
        .orElseThrow(() -> new ChatException(ChatErrorCode.NOT_PARTICIPANT));
    if (restrictions.existsById(new ChatRestrictionId(roomId, sender.id()))) {
      throw new ChatException(ChatErrorCode.CHAT_BLOCKED);
    }
    validateContent(request.content());
    boolean owner = room.ownerId().equals(sender.userId());
    String parentPreview = resolveParentPreview(roomId, request.parentId(), owner);
    var message = messages.save(new ChatMessage(roomId, sender.id(), request.content(), request.parentId(),
        Instant.now(clock).truncatedTo(ChronoUnit.MICROS)));
    events.publishEvent(ChatMessageEvent.of(roomId, new ChatMessageResponse(message.getId(), sender.id(),
        sender.nickname(), owner, message.getContent(), message.getParentId(), parentPreview, message.getCreatedAt())));
  }

  private void validateContent(String content) {
    // MySQL CHAR_LENGTH와 같은 문자 기준으로 센다. 이모지는 UTF-16 길이가 2여도 한 글자다.
    if (content == null || content.isBlank() || content.codePointCount(0, content.length()) > 300
        || containsUnpairedSurrogate(content)) {
      throw new ChatException(ChatErrorCode.VALIDATION_FAILED);
    }
  }

  private boolean containsUnpairedSurrogate(String content) {
    // 정상 쌍은 codePoints()에서 하나로 합쳐지므로 서로게이트 범위에 남는 값은 잘못된 입력이다.
    return content.codePoints().anyMatch(codePoint ->
        codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE);
  }

  private String resolveParentPreview(Long roomId, Long parentId, boolean owner) {
    if (parentId == null) return null;
    if (!owner) throw new ChatException(ChatErrorCode.FORBIDDEN);
    return messages.findByIdAndRoomIdAndDeletedAtIsNull(parentId, roomId)
        .orElseThrow(() -> new ChatException(ChatErrorCode.VALIDATION_FAILED)).getContent();
  }
}
