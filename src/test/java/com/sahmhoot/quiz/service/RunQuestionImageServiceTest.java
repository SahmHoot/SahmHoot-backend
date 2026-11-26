package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.questionset.service.port.QuestionImageAccess.Failure;
import com.sahmhoot.questionset.service.port.QuestionImageAccess.ImageAccessException;
import com.sahmhoot.questionset.service.port.QuestionImageAccess.Metadata;
import com.sahmhoot.quiz.controller.RunQuestionImageController;
import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.entity.QuizRunStatus;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.exception.QuizRestExceptionHandler;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomSnapshot;
import com.sahmhoot.quiz.service.port.RoomStatus;
import com.sahmhoot.quiz.service.port.RunImageAccess;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RunQuestionImageServiceTest {
  private static final UUID KEY = UUID.randomUUID();
  private static final String URL = "/api/rooms/1/quiz-runs/2/questions/3/image";
  private final RoomPort rooms = mock(RoomPort.class);
  private final RoomLockManager locks = new RoomLockManager();
  private final QuizRunRepository runs = mock(QuizRunRepository.class);
  private final RunQuestionRepository questions = mock(RunQuestionRepository.class);
  private final RunImageAccess images = mock(RunImageAccess.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
  private final RunQuestionImageService service = new RunQuestionImageService(rooms, locks, runs, questions,
      images, transactions);
  private final MockMvc mvc = MockMvcBuilders
      .standaloneSetup(new RunQuestionImageController(service))
      .setControllerAdvice(new QuizRestExceptionHandler()).build();

  @BeforeEach
  void setUp() {
    when(transactions.getTransaction(any())).thenReturn(transaction);
    when(rooms.findRoom(1L)).thenReturn(Optional.of(new RoomSnapshot(1L, 5L, RoomStatus.PLAYING)));
    when(rooms.findParticipantId(1L, 5L)).thenReturn(Optional.of(6L));
    when(runs.findTopByRoomIdOrderByIdDesc(1L))
        .thenReturn(Optional.of(QuizRun.builder().id(2L).status(QuizRunStatus.RUNNING).build()));
  }

  @Test
  void readsBytesAfterTransactionAndRoomLockAreReleased() throws Exception {
    when(questions.findFirstByQuizRunIdAndStatus(2L, RunQuestionStatus.OPEN))
        .thenReturn(Optional.of(RunQuestion.builder().id(3L).build()));
    when(images.runImages(2L, 3L)).thenReturn(Map.of(3L, new Metadata(KEY, "image/png", 1, 1)));
    byte[] bytes = {1, 2, 3};
    when(images.read(KEY)).thenAnswer(invocation -> {
      verify(transactions).commit(transaction);
      assertThat(locks.isLocked(1L)).isFalse();
      return bytes;
    });
    mvc.perform(get(URL).principal(() -> "5"))
        .andExpect(status().isOk()).andExpect(content().bytes(bytes))
        .andExpect(content().contentType("image/png"))
        .andExpect(header().string("Content-Length", "3"))
        .andExpect(header().string("Cache-Control", "no-cache, private"))
        .andExpect(header().string("Vary", "Authorization"))
        .andExpect(header().exists("ETag"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }

  @Test
  void revalidatesCachedImageAgainstCurrentAccessAndContent() throws Exception {
    when(questions.findFirstByQuizRunIdAndStatus(2L, RunQuestionStatus.OPEN))
        .thenReturn(Optional.of(RunQuestion.builder().id(3L).build()));
    when(images.runImages(2L, 3L)).thenReturn(Map.of(3L, new Metadata(KEY, "image/png", 1, 1)));
    when(images.read(KEY)).thenReturn(new byte[] {1, 2, 3});
    String tag = mvc.perform(get(URL).principal(() -> "5"))
        .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");

    mvc.perform(get(URL).principal(() -> "5").header("If-None-Match", tag))
        .andExpect(status().isNotModified()).andExpect(content().bytes(new byte[0]))
        .andExpect(header().string("Cache-Control", "no-cache, private"))
        .andExpect(header().string("Vary", "Authorization"))
        .andExpect(header().string("ETag", tag));
    mvc.perform(get(URL).principal(() -> "5").header("If-None-Match", "\"stale\""))
        .andExpect(status().isOk()).andExpect(content().bytes(new byte[] {1, 2, 3}));
    mvc.perform(get(URL).principal(() -> "7").header("If-None-Match", tag))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"))
        .andExpect(header().doesNotExist("ETag"));

    when(questions.findFirstByQuizRunIdAndStatus(2L, RunQuestionStatus.OPEN))
        .thenReturn(Optional.of(RunQuestion.builder().id(9L).build()));
    mvc.perform(get(URL).principal(() -> "5").header("If-None-Match", tag))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUESTION_IMAGE_NOT_FOUND"))
        .andExpect(header().doesNotExist("ETag"));

    when(runs.findTopByRoomIdOrderByIdDesc(1L))
        .thenReturn(Optional.of(QuizRun.builder().id(2L).status(QuizRunStatus.FINISHED).build()));
    mvc.perform(get(URL).principal(() -> "5").header("If-None-Match", tag))
        .andExpect(status().isNotModified());

    when(rooms.findRoom(1L)).thenReturn(Optional.of(new RoomSnapshot(1L, 5L, RoomStatus.OPEN)));
    mvc.perform(get(URL).principal(() -> "5").header("If-None-Match", tag))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUIZ_RUN_NOT_FOUND"))
        .andExpect(header().doesNotExist("ETag"));
    verify(images, times(4)).read(KEY);
  }

  @Test
  void ownerCannotProbeFutureQuestionOrTriggerStorageAccess() throws Exception {
    when(questions.findFirstByQuizRunIdAndStatus(2L, RunQuestionStatus.OPEN))
        .thenReturn(Optional.of(RunQuestion.builder().id(9L).build()));
    mvc.perform(get(URL).principal(() -> "5"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUESTION_IMAGE_NOT_FOUND"));
    verifyNoInteractions(images);
  }

  @Test
  void feedbackAllowsCurrentImageAndTranslatesTemporaryFailure() throws Exception {
    when(questions.findFirstByQuizRunIdAndStatusOrderByOrderNoDesc(2L, RunQuestionStatus.CLOSED))
        .thenReturn(Optional.of(RunQuestion.builder().id(3L).build()));
    when(images.runImages(2L, 3L)).thenReturn(Map.of(3L, new Metadata(KEY, "image/png", 1, 1)));
    when(images.read(KEY)).thenThrow(new ImageAccessException(Failure.TEMPORARY_STORAGE));
    mvc.perform(get(URL).principal(() -> "5"))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IMAGE_STORAGE_UNAVAILABLE"));
  }
}
