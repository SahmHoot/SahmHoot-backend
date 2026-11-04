package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.room.dto.RoomStateResponse.*;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.exception.RoomErrorCode;
import com.sahmhoot.room.exception.RoomException;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.RoomStateService;
import com.sahmhoot.room.service.RoomUserLookup;
import com.sahmhoot.room.service.port.RoomQuizStatePort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RoomStateServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
  private final RoomRepository rooms = mock(RoomRepository.class);
  private final RoomParticipantRepository participants = mock(RoomParticipantRepository.class);
  private final RoomUserLookup users = mock(RoomUserLookup.class);
  private final RoomLockManager locks = new RoomLockManager();
  @SuppressWarnings("unchecked")
  private final ObjectProvider<RoomQuizStatePort> providers = mock(ObjectProvider.class);
  private final RoomQuizStatePort quizzes = mock(RoomQuizStatePort.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final RoomStateService service = new RoomStateService(rooms, participants, users, locks,
      providers, Clock.fixed(NOW, ZoneOffset.UTC), transactions, "https://class.example/");
  private Room room;

  @BeforeEach void setup() {
    room = new Room(1L, "자료구조", "123456");
    ReflectionTestUtils.setField(room, "id", 42L);
    when(rooms.findById(42L)).thenReturn(Optional.of(room));
    when(users.findById(1L)).thenReturn(Optional.of(new RoomUserLookup.RoomUser(1L, "PROFESSOR", "담당교수")));
    when(users.findById(2L)).thenReturn(Optional.of(new RoomUserLookup.RoomUser(2L, "STUDENT", "실명비공개")));
    member(1L, 100L, "담당교수");
    member(2L, 200L, "학생닉네임");
    when(providers.getIfAvailable()).thenReturn(quizzes);
    when(quizzes.read(anyLong(), anyLong())).thenReturn(RoomQuizStatePort.Snapshot.waiting());
    when(transactions.getTransaction(any())).thenAnswer(call -> {
      assertThat(locks.isLocked(42L)).isTrue();
      var definition = call.getArgument(0, TransactionDefinition.class);
      assertThat(definition.isReadOnly()).isTrue();
      assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      return new SimpleTransactionStatus();
    });
  }

  @Test void waitingSnapshotContainsRoomAndOnlyAuthenticatedParticipantsNickname() {
    var response = service.state(42L, 2L);
    assertThat(response.serverTime()).isEqualTo(NOW);
    assertThat(response.room().ownerName()).isEqualTo("담당교수");
    assertThat(response.room().joinUrl()).isEqualTo("https://class.example/join/123456");
    assertThat(response.me()).isEqualTo(new Me(200L, "학생닉네임", false, false));
    assertThat(response.quiz()).isNull();
    assertThat(response.progress()).isNull();
    assertThat(response.reactionStats()).isNull();
    assertThat(response.onlineCount()).isZero();
    assertThat(response.recentMessages()).isEmpty();
    verify(quizzes).read(42L, 200L);
    verify(transactions).commit(any());
    assertThat(locks.isLocked(42L)).isFalse();
  }

  @Test void ownershipUsesRoomHostRatherThanAccountRole() {
    when(users.findById(2L)).thenReturn(Optional.of(new RoomUserLookup.RoomUser(2L, "PROFESSOR", "다른교수")));
    assertThat(service.state(42L, 2L).me().isOwner()).isFalse();
    when(users.findById(1L)).thenReturn(Optional.of(new RoomUserLookup.RoomUser(1L, "STUDENT", "담당교수")));
    var response = service.state(42L, 1L);
    assertThat(response.me().isOwner()).isTrue();
    assertThat(response.reactionStats()).isEmpty();
  }

  @Test void deletedUserIsUnauthorizedBeforeReadingRoomOrQuiz() {
    when(users.findById(2L)).thenReturn(Optional.empty());
    assertError(2L, RoomErrorCode.UNAUTHORIZED);
    verifyNoInteractions(rooms, participants, quizzes);
  }

  @Test void missingAndClosedRoomsDoNotReadParticipantOrQuizState() {
    when(rooms.findById(42L)).thenReturn(Optional.empty());
    assertError(2L, RoomErrorCode.ROOM_NOT_FOUND);
    when(rooms.findById(42L)).thenReturn(Optional.of(room));
    room.close(NOW);
    assertError(2L, RoomErrorCode.ROOM_CLOSED);
    verifyNoInteractions(participants, quizzes);
  }

  @Test void absentOrInactiveParticipantIsForbiddenBeforeReadingQuiz() {
    when(participants.findByRoomIdAndUserIdAndLeftAtIsNull(42L, 2L)).thenReturn(Optional.empty());
    assertError(2L, RoomErrorCode.NOT_PARTICIPANT);
    verifyNoInteractions(quizzes);
  }

  @ParameterizedTest @EnumSource(Phase.class)
  void phaseControlsAnswerResultAndOwnerProgressExposure(Phase phase) {
    room.startPlaying();
    var snapshot = snapshot(phase);
    when(quizzes.read(anyLong(), anyLong())).thenReturn(snapshot);
    var student = service.state(42L, 2L);
    var owner = service.state(42L, 1L);
    assertThat(student.quiz().phase()).isEqualTo(phase);
    assertThat(student.quiz().myAnswers()).containsExactly(new MyAnswer(812L, 3001L));
    assertThat(student.progress()).isNull();
    assertThat(student.reactionStats()).isNull();
    assertThat(owner.quiz().myAnswers()).isNull();
    assertThat(owner.progress()).isEqualTo(phase == Phase.QUESTION ? snapshot.progress() : null);
    assertThat(owner.reactionStats()).isEmpty();
    if (phase == Phase.RESULT) {
      assertThat(student.quiz().currentQuestion()).isNull();
      assertThat(student.quiz().result()).isEqualTo(snapshot.quiz().result());
    } else {
      assertThat(student.quiz().currentQuestion().correctChoiceId())
          .isEqualTo(phase == Phase.FEEDBACK ? 3001L : null);
      assertThat(owner.quiz().currentQuestion().correctChoiceId())
          .isEqualTo(phase == Phase.FEEDBACK ? 3001L : null);
      assertThat(student.quiz().result()).isNull();
    }
    assertThat(student.quiz().nextOpensAt()).isEqualTo(phase == Phase.FEEDBACK ? NOW.plusSeconds(3) : null);
  }

  @Test void playingWithoutProviderFailsInsteadOfPretendingToBeWaiting() {
    when(providers.getIfAvailable()).thenReturn(null);
    assertThat(service.state(42L, 2L).quiz()).isNull();
    room.startPlaying();
    assertThatThrownBy(() -> service.state(42L, 2L)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RoomQuizStatePort");
    verify(transactions).rollback(any());
    assertThat(locks.isLocked(42L)).isFalse();
  }

  @Test void providerFailureRollsBackReadAndReleasesRoomLock() {
    when(quizzes.read(anyLong(), anyLong())).thenThrow(new IllegalStateException("조회 실패"));
    assertThatThrownBy(() -> service.state(42L, 2L)).hasMessage("조회 실패");
    verify(transactions).rollback(any());
    assertThat(locks.isLocked(42L)).isFalse();
  }

  @Test void inconsistentRunStatusIsRejected() {
    room.startPlaying();
    var state = snapshot(Phase.QUESTION);
    when(quizzes.read(anyLong(), anyLong())).thenReturn(new RoomQuizStatePort.Snapshot(
        new QuizRun(RunStatus.ABORTED), state.quiz(), state.progress()));
    assertThatThrownBy(() -> service.state(42L, 2L)).isInstanceOf(IllegalStateException.class);
  }

  @Test void feedbackWithoutCommittedCloseCannotRevealCorrectChoice() {
    room.startPlaying();
    var open = snapshot(Phase.QUESTION).quiz();
    var invalid = new Quiz(open.runId(), open.questionSetTitle(), open.totalQuestions(), Phase.FEEDBACK,
        open.currentQuestion(), NOW.plusSeconds(3), open.myAnswers(), open.result());
    when(quizzes.read(anyLong(), anyLong())).thenReturn(new RoomQuizStatePort.Snapshot(
        new QuizRun(RunStatus.RUNNING), invalid, null));
    assertThatThrownBy(() -> service.state(42L, 2L)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("정답을 공개할 수 없습니다");
  }

  private void member(Long userId, Long id, String nickname) {
    var member = new RoomParticipant(42L, userId, nickname);
    ReflectionTestUtils.setField(member, "id", id);
    when(participants.findByRoomIdAndUserIdAndLeftAtIsNull(42L, userId)).thenReturn(Optional.of(member));
  }

  private void assertError(Long userId, RoomErrorCode code) {
    assertThatThrownBy(() -> service.state(42L, userId)).isInstanceOfSatisfying(RoomException.class,
        error -> assertThat(error.getErrorCode()).isEqualTo(code));
  }

  static RoomQuizStatePort.Snapshot snapshot(Phase phase) {
    var question = new CurrentQuestion(812L, 3, "MULTIPLE_CHOICE", 15, "현재 문항",
        List.of(new Choice(3001L, 1, "선택지")), NOW.minusSeconds(10), NOW.plusSeconds(5),
        phase == Phase.QUESTION ? null : NOW, 3001L);
    var result = new Result(List.of(new QuestionResult(812L, 3, "현재 문항", 3001L, 2, 1, 1,
        List.of(new ChoiceResult(3001L, 1, "선택지", 1)))));
    return new RoomQuizStatePort.Snapshot(new QuizRun(phase == Phase.RESULT ? RunStatus.FINISHED : RunStatus.RUNNING),
        new Quiz(77L, "문제집", 5, phase, question, NOW.plusSeconds(3),
            List.of(new MyAnswer(812L, 3001L)), result), new Progress(812L, 2));
  }
}
