package com.sahmhoot.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahmhoot.chat.entity.*;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChatEntityMappingIntegrationTest {
  private static final Instant TIME = Instant.parse("2030-01-01T12:34:56.123456Z");
  @Autowired EntityManager em;
  @Autowired JdbcTemplate jdbc;
  private Long professorId;
  private Long studentId;
  private Long roomId;
  private Long participantId;
  private Long ownerParticipantId;

  @BeforeEach
  void setup() {
    var professor = User.create(UUID.randomUUID() + "@test.invalid", "test-only", "Professor", null, Role.PROFESSOR);
    var student = User.create(UUID.randomUUID() + "@test.invalid", "test-only", "Student", null, Role.STUDENT);
    em.persist(professor);
    em.persist(student);
    professorId = professor.getId();
    studentId = student.getId();
    var room = new Room(professorId, "Mapping test", UUID.randomUUID().toString().substring(0, 6));
    em.persist(room);
    roomId = room.getId();
    var participant = new RoomParticipant(roomId, studentId, "Anonymous");
    em.persist(participant);
    participantId = participant.getId();
    var owner = new RoomParticipant(roomId, professorId, "Owner nickname");
    em.persist(owner);
    ownerParticipantId = owner.getId();
    em.flush();
  }

  @Test
  void messagesRoundTripGeneratedIdsNullableFieldsRepliesAndMicrosecondTimes() {
    var student = new ChatMessage(roomId, participantId, "질문😀", null, TIME);
    em.persist(student);
    var professor = new ChatMessage(roomId, ownerParticipantId, "답변", student.getId(), TIME.plusSeconds(1));
    em.persist(professor);
    em.flush();
    em.clear();

    assertThat(student.getId()).isPositive();
    var storedStudent = em.find(ChatMessage.class, student.getId());
    assertThat(storedStudent).extracting(ChatMessage::getRoomId,
        ChatMessage::getSenderParticipantId, ChatMessage::getContent, ChatMessage::getParentId,
        ChatMessage::getCreatedAt, ChatMessage::getDeletedAt)
        .containsExactly(roomId, participantId, "질문😀", null, TIME, null);
    var storedProfessor = em.find(ChatMessage.class, professor.getId());
    assertThat(storedProfessor).extracting(ChatMessage::getRoomId,
        ChatMessage::getSenderParticipantId, ChatMessage::getContent, ChatMessage::getParentId,
        ChatMessage::getCreatedAt, ChatMessage::getDeletedAt)
        .containsExactly(roomId, ownerParticipantId, "답변", student.getId(), TIME.plusSeconds(1), null);

    // Existing soft-deleted rows must also be readable; deletion policy belongs to the service.
    jdbc.update("UPDATE chat_messages SET deleted_at = ? WHERE id = ?", Timestamp.from(TIME.plusSeconds(2)), student.getId());
    em.clear();
    assertThat(em.find(ChatMessage.class, student.getId()).getDeletedAt()).isEqualTo(TIME.plusSeconds(2));
  }

  @Test
  void nonOwnerProfessorUsesTheirParticipantNickname() {
    var guest = User.create(UUID.randomUUID() + "@test.invalid", "test-only", "Account name", null, Role.PROFESSOR);
    em.persist(guest);
    var participant = new RoomParticipant(roomId, guest.getId(), "Guest nickname");
    em.persist(participant);
    var message = new ChatMessage(roomId, participant.getId(), "Guest message", null, TIME);
    em.persist(message);
    em.flush();
    em.clear();

    var stored = em.find(ChatMessage.class, message.getId());
    var sender = em.find(RoomParticipant.class, stored.getSenderParticipantId());
    assertThat(sender.getNickname()).isEqualTo("Guest nickname");
    assertThat(sender.getUserId()).isEqualTo(guest.getId()).isNotEqualTo(professorId);
  }

  @Test
  void pinsRoundTripCompositeKeysAndCanBeRemovedIndependently() {
    var first = new ChatMessage(roomId, ownerParticipantId, "First", null, TIME);
    var second = new ChatMessage(roomId, ownerParticipantId, "Second", null, TIME);
    em.persist(first);
    em.persist(second);
    var firstId = new ChatPinId(roomId, first.getId());
    var secondId = new ChatPinId(roomId, second.getId());
    em.persist(new ChatPin(firstId, professorId, TIME));
    em.persist(new ChatPin(secondId, professorId, TIME.plusSeconds(1)));
    em.flush();
    em.clear();

    var stored = em.find(ChatPin.class, new ChatPinId(roomId, first.getId()));
    assertThat(stored.getId()).isEqualTo(firstId).hasSameHashCodeAs(firstId);
    assertThat(stored.getId()).extracting(ChatPinId::getRoomId, ChatPinId::getMessageId)
        .containsExactly(roomId, first.getId());
    assertThat(stored.getPinnedByUserId()).isEqualTo(professorId);
    assertThat(stored.getPinnedAt()).isEqualTo(TIME);
    em.remove(stored);
    em.flush();
    em.clear();
    assertThat(em.find(ChatPin.class, firstId)).isNull();
    assertThat(em.find(ChatPin.class, secondId).getPinnedAt()).isEqualTo(TIME.plusSeconds(1));
    assertThat(em.find(ChatMessage.class, first.getId())).isNotNull();
  }

  @Test
  void restrictionsRoundTripCompositeKeysAndReleasePreservesParticipants() {
    var otherRoom = new Room(professorId, "Other room", UUID.randomUUID().toString().substring(0, 6));
    em.persist(otherRoom);
    var otherParticipant = new RoomParticipant(otherRoom.getId(), studentId, "Other nickname");
    em.persist(otherParticipant);
    var firstId = new ChatRestrictionId(roomId, participantId);
    var secondId = new ChatRestrictionId(otherRoom.getId(), otherParticipant.getId());
    em.persist(new ChatRestriction(firstId, professorId, TIME));
    em.persist(new ChatRestriction(secondId, professorId, TIME.plusSeconds(1)));
    em.flush();
    em.clear();

    var stored = em.find(ChatRestriction.class, new ChatRestrictionId(roomId, participantId));
    assertThat(stored.getId()).isEqualTo(firstId).hasSameHashCodeAs(firstId);
    assertThat(stored.getId()).extracting(ChatRestrictionId::getRoomId, ChatRestrictionId::getParticipantId)
        .containsExactly(roomId, participantId);
    assertThat(stored.getRestrictedByUserId()).isEqualTo(professorId);
    assertThat(stored.getRestrictedAt()).isEqualTo(TIME);
    em.remove(stored);
    em.flush();
    em.clear();
    assertThat(em.find(ChatRestriction.class, firstId)).isNull();
    assertThat(em.find(ChatRestriction.class, secondId).getRestrictedAt()).isEqualTo(TIME.plusSeconds(1));
    assertThat(em.find(RoomParticipant.class, participantId)).isNotNull();
  }
}
