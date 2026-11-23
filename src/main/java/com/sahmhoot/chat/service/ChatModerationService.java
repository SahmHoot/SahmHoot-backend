package com.sahmhoot.chat.service;

import com.sahmhoot.chat.dto.ChatAuthorResponse;
import com.sahmhoot.chat.dto.ChatMessageResponse;
import com.sahmhoot.chat.dto.ChatRestrictionResponse;
import com.sahmhoot.chat.entity.ChatMessage;
import com.sahmhoot.chat.entity.ChatPin;
import com.sahmhoot.chat.entity.ChatPinId;
import com.sahmhoot.chat.entity.ChatRestriction;
import com.sahmhoot.chat.entity.ChatRestrictionId;
import com.sahmhoot.chat.event.ChatMessageDeletedEvent;
import com.sahmhoot.chat.event.ChatStatusChangedEvent;
import com.sahmhoot.chat.event.NoticePinnedEvent;
import com.sahmhoot.chat.event.NoticeUnpinnedEvent;
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
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatModerationService {
  private static final String EMAIL_MASK = "***";
  private static final String EMAIL_ATOM = "[\\p{L}\\p{M}\\p{N}!#$%&'*+/=?^_`{|}~-]+";
  private static final String DOMAIN_LABEL = "[\\p{L}\\p{N}](?:[\\p{L}\\p{M}\\p{N}-]*[\\p{L}\\p{M}\\p{N}])?";
  private static final Pattern ACCOUNT_EMAIL =
      Pattern.compile(EMAIL_ATOM + "(?:\\." + EMAIL_ATOM + ")*@" + DOMAIN_LABEL + "(?:\\." + DOMAIN_LABEL + ")*");

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
    var maskedEmail = authors.findEmail(sender.userId())
        .map(ChatModerationService::maskEmail)
        .orElseThrow(() -> new ChatException(ChatErrorCode.INTERNAL_ERROR));
    return new ChatAuthorResponse(sender.id(), sender.nickname(), maskedEmail);
  }

  private static String maskEmail(String email) {
    if (!ACCOUNT_EMAIL.matcher(email).matches()) return EMAIL_MASK;

    int at = email.indexOf('@');
    int visible = Math.min(3, email.codePointCount(0, at) - 1);
    int prefixEnd = email.offsetByCodePoints(0, visible);
    // An address whose hidden part is already "***" must still change in the response.
    if (email.substring(prefixEnd, at).equals(EMAIL_MASK)) {
      prefixEnd = email.offsetByCodePoints(0, visible - 1);
    }
    return email.substring(0, prefixEnd) + EMAIL_MASK + email.substring(at);
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
        boolean pinned = pins.removePin(roomId, messageId) > 0;
        events.publishEvent(ChatMessageDeletedEvent.of(roomId, messageId, now));
        if (pinned) events.publishEvent(NoticeUnpinnedEvent.of(roomId, messageId, now));
      }
      return null;
    });
  }

  /** 이미 고정된 메시지는 처음 고정한 순서를 유지하고 다시 발행하지 않는다. */
  public ChatMessageResponse pin(Long roomId, Long ownerId, Long messageId) {
    return write(roomId, () -> {
      requireOpenRoomOwner(roomId, ownerId);
      var message = messages.findByIdAndRoomId(messageId, roomId)
          .orElseThrow(() -> new ChatException(ChatErrorCode.MESSAGE_NOT_FOUND));
      if (message.getDeletedAt() != null) throw new ChatException(ChatErrorCode.MESSAGE_DELETED);
      var response = render(roomId, ownerId, message);
      var id = new ChatPinId(roomId, messageId);
      if (pins.existsById(id)) return response;
      Instant now = now();
      pins.save(new ChatPin(id, ownerId, now));
      events.publishEvent(NoticePinnedEvent.of(roomId, response, now));
      return response;
    });
  }

  /** 고정되지 않은 메시지(없는 메시지 포함)의 해제는 바뀌는 것이 없어 발행 없이 성공한다. */
  public void unpin(Long roomId, Long ownerId, Long messageId) {
    write(roomId, () -> {
      requireOpenRoomOwner(roomId, ownerId);
      if (pins.removePin(roomId, messageId) > 0) {
        events.publishEvent(NoticeUnpinnedEvent.of(roomId, messageId, now()));
      }
      return null;
    });
  }

  private ChatMessageResponse render(Long roomId, Long ownerId, ChatMessage message) {
    var sender = rooms.findParticipants(roomId, Set.of(message.getSenderParticipantId()))
        .get(message.getSenderParticipantId());
    if (sender == null) throw new ChatException(ChatErrorCode.INTERNAL_ERROR);
    // state 복구와 같이 삭제된 원본의 미리보기는 보내지 않는다.
    String parentPreview = message.getParentId() == null ? null
        : messages.findByIdAndRoomIdAndDeletedAtIsNull(message.getParentId(), roomId)
            .map(ChatMessage::getContent).orElse(null);
    return new ChatMessageResponse(message.getId(), sender.id(), sender.nickname(),
        ownerId.equals(sender.userId()), message.getContent(), message.getParentId(), parentPreview,
        message.getCreatedAt());
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
