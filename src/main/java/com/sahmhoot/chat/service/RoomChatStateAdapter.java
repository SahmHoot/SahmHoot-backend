package com.sahmhoot.chat.service;

import com.sahmhoot.chat.entity.ChatMessage;
import com.sahmhoot.chat.entity.ChatRestrictionId;
import com.sahmhoot.chat.repository.ChatMessageRepository;
import com.sahmhoot.chat.repository.ChatRestrictionRepository;
import com.sahmhoot.chat.service.port.ChatRoomPort;
import com.sahmhoot.room.dto.RoomStateResponse.Message;
import com.sahmhoot.room.service.port.RoomChatStatePort;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RoomChatStateAdapter implements RoomChatStatePort {
  private final ChatMessageRepository messages;
  private final ChatRestrictionRepository restrictions;
  private final ChatRoomPort rooms;

  @Override
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Snapshot read(Long roomId, Long participantId) {
    var room = rooms.findRoom(roomId).orElseThrow(() -> new IllegalStateException("채팅 복구 대상 방이 없습니다."));
    var recent = messages.findTop50ByRoomIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(roomId);
    var pinned = messages.findPinnedMessages(roomId);
    var all = Stream.concat(recent.stream(), pinned.stream()).toList();
    var senders = rooms.findParticipants(roomId, all.stream().map(ChatMessage::getSenderParticipantId)
        .collect(Collectors.toSet()));
    var parents = findParents(roomId, all);
    boolean blocked = restrictions.existsById(new ChatRestrictionId(roomId, participantId));
    return new Snapshot(blocked, render(recent, room.ownerId(), senders, parents),
        render(pinned, room.ownerId(), senders, parents));
  }

  private Map<Long, ChatMessage> findParents(Long roomId, List<ChatMessage> source) {
    Set<Long> parentIds = source.stream().map(ChatMessage::getParentId).filter(Objects::nonNull)
        .collect(Collectors.toSet());
    if (parentIds.isEmpty()) return Map.of();
    return messages.findByRoomIdAndIdIn(roomId, parentIds).stream()
        .collect(Collectors.toMap(ChatMessage::getId, Function.identity()));
  }

  private List<Message> render(List<ChatMessage> source, Long ownerId,
      Map<Long, ChatRoomPort.Participant> senders, Map<Long, ChatMessage> parents) {
    return source.stream().map(message -> {
      var sender = Objects.requireNonNull(senders.get(message.getSenderParticipantId()), "메시지 작성자가 없습니다.");
      var parent = message.getParentId() == null ? null : parents.get(message.getParentId());
      String preview = parent == null || parent.getDeletedAt() != null ? null : parent.getContent();
      return new Message(message.getId(), sender.id(), sender.nickname(), ownerId.equals(sender.userId()),
          message.getContent(), message.getParentId(), preview, message.getCreatedAt());
    }).toList();
  }
}
