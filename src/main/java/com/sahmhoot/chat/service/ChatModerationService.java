package com.sahmhoot.chat.service;

import com.sahmhoot.chat.dto.ChatAuthorResponse;
import com.sahmhoot.chat.dto.ChatRestrictionResponse;
import com.sahmhoot.chat.entity.ChatMessage;
import com.sahmhoot.chat.entity.ChatRestriction;
import com.sahmhoot.chat.entity.ChatRestrictionId;
import com.sahmhoot.chat.event.ChatMessageDeletedEvent;
import com.sahmhoot.chat.event.ChatStatusChangedEvent;
import com.sahmhoot.chat.exception.ChatErrorCode;
import com.sahmhoot.chat.exception.ChatException;
import com.sahmhoot.chat.repository.ChatMessageRepository;
import com.sahmhoot.chat.repository.ChatPinRepository;
import com.sahmhoot.chat.repository.ChatRestrictionRepository;
import com.sahmhoot.chat.service.port.ChatAuthorEmailPort;
import com.sahmhoot.chat.service.port.ChatRoomPort;
import com.sahmhoot.quiz.service.port.RoomLockPort;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatModerationService {
  private final RoomLockPort locks;
  private final ChatRoomPort rooms;
  private final ChatAuthorEmailPort authors;
  private final ChatMessageRepository messages;
  private final ChatPinRepository pins;
  private final ChatRestrictionRepository restrictions;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final TransactionTemplate writing;

  public ChatModerationService(RoomLockPort locks, ChatRoomPort rooms, ChatAuthorEmailPort authors,
      ChatMessageRepository messages, ChatPinRepository pins, ChatRestrictionRepository restrictions,
      ApplicationEventPublisher events, Clock clock, PlatformTransactionManager transactions) {
    this.locks = locks;
    this.rooms = rooms;
    this.authors = authors;
    this.messages = messages;
    this.pins = pins;
    this.restrictions = restrictions;
    this.events = events;
    this.clock = clock;
    this.writing = new TransactionTemplate(transactions);
  }

  @Transactional(readOnly = true)
  public ChatAuthorResponse author(Long roomId, Long ownerId, Long messageId) {
    requireOwner(roomId, ownerId);
    var message = requireMessage(roomId, messageId);
    var sender = rooms.findParticipants(roomId, Set.of(message.getSenderParticipantId()))
        .get(message.getSenderParticipantId());
    if (sender == null) throw new ChatException(ChatErrorCode.INTERNAL_ERROR);
    var email = authors.findEmail(sender.userId())
        .orElseThrow(() -> new ChatException(ChatErrorCode.INTERNAL_ERROR));
    return new ChatAuthorResponse(sender.id(), sender.nickname(), email);
  }

  public ChatRestrictionResponse updateRestriction(Long roomId, Long ownerId, Long participantId, boolean blocked) {
    return write(roomId, () -> {
      requireOpenRoomOwner(roomId, ownerId);
      var target = rooms.findParticipants(roomId, Set.of(participantId)).get(participantId);
      if (target == null) throw new ChatException(ChatErrorCode.PARTICIPANT_NOT_FOUND);
      if (blocked && ownerId.equals(target.userId())) throw new ChatException(ChatErrorCode.CANNOT_BLOCK_OWNER);

      var id = new ChatRestrictionId(roomId, participantId);
      var response = new ChatRestrictionResponse(target.id(), target.nickname(), blocked);
      if (restrictions.existsById(id) == blocked) return response;

      Instant now = now();
      if (blocked) {
        restrictions.save(new ChatRestriction(id, ownerId, now));
      } else {
        restrictions.deleteById(id);
      }
      events.publishEvent(ChatStatusChangedEvent.of(roomId, target.userId(), now, blocked));
      return response;
    });
  }

  public void delete(Long roomId, Long ownerId, Long messageId) {
    write(roomId, () -> {
      requireOpenRoomOwner(roomId, ownerId);
      var message = requireMessage(roomId, messageId);
      Instant now = now();
      if (message.markDeleted(now)) {
        pins.removePin(roomId, messageId);
        events.publishEvent(ChatMessageDeletedEvent.of(roomId, messageId, now));
      }
      return null;
    });
  }

  private ChatMessage requireMessage(Long roomId, Long messageId) {
    return messages.findByIdAndRoomId(messageId, roomId)
        .orElseThrow(() -> new ChatException(ChatErrorCode.MESSAGE_NOT_FOUND));
  }

  private ChatRoomPort.Room requireOwner(Long roomId, Long userId) {
    var room = rooms.findRoom(roomId).orElseThrow(() -> new ChatException(ChatErrorCode.ROOM_NOT_FOUND));
    if (!room.ownerId().equals(userId)) throw new ChatException(ChatErrorCode.FORBIDDEN);
    return room;
  }

  private void requireOpenRoomOwner(Long roomId, Long userId) {
    if (requireOwner(roomId, userId).closed()) throw new ChatException(ChatErrorCode.ROOM_CLOSED);
  }

  private <T> T write(Long roomId, Supplier<T> change) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Chat moderation requires the room lock before starting a transaction");
    }
    // 송신, 수업 종료와 같은 방 잠금을 커밋 및 이벤트 발행까지 유지한다.
    return locks.executeWithLock(roomId, () -> writing.execute(tx -> change.get()));
  }

  private Instant now() {
    return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
  }
}
