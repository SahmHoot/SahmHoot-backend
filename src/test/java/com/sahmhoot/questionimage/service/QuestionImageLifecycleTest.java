package com.sahmhoot.questionimage.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.questionimage.service.QuestionImageLifecycleRepository.Reservation;
import com.sahmhoot.questionimage.storage.ImageStorage;
import com.sahmhoot.questionimage.storage.ImageStorageException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

class QuestionImageLifecycleTest {
  private final Instant now = Instant.parse("2026-11-24T00:00:00Z");
  private final UUID key = UUID.randomUUID();
  private final ImageStorage storage = mock(ImageStorage.class);
  private final QuestionImageValidator validator = mock(QuestionImageValidator.class);
  private final QuestionImageLifecycleRepository repository = mock(QuestionImageLifecycleRepository.class);
  private final QuestionImageLifecycle lifecycle =
      new QuestionImageLifecycle(validator, repository, storage, Clock.fixed(now, ZoneOffset.UTC), 1);

  @Test
  void deniesStorageForUsersWithoutSetOwnership() throws Exception {
    assertThatThrownBy(() -> lifecycle.upload(1, 2, InputStream.nullInputStream(), "image/png"))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(storage, validator);
  }

  @Test
  void recordsExpiryAndReservesBeforeWriting() throws Exception {
    givenValidatedImage();
    when(repository.reserve(any())).thenReturn(true);
    when(repository.finishUpload(eq(key), eq(now), any()))
        .thenAnswer(call -> call.<BooleanSupplier>getArgument(2).getAsBoolean());
    assertThat(lifecycle.upload(1, 2, InputStream.nullInputStream(), "image/png")).isEqualTo(key);
    var reservation = ArgumentCaptor.forClass(Reservation.class);
    var ordered = inOrder(repository, storage);
    ordered.verify(repository).reserve(reservation.capture());
    ordered.verify(storage).save(eq(key), eq("image/png"), any());
    assertThat(reservation.getValue().createdAt()).isEqualTo(now);
    assertThat(reservation.getValue().expiresAt()).isEqualTo(now.plusSeconds(86_400));
  }

  @Test
  void delegatesRecoveryFromUncertainUploadWithoutDeletingImmediately() throws Exception {
    givenValidatedImage();
    when(repository.reserve(any())).thenReturn(true);
    when(repository.finishUpload(eq(key), eq(now), any()))
        .thenAnswer(call -> call.<BooleanSupplier>getArgument(2).getAsBoolean());
    var unavailable = new ImageStorageException(ImageStorageException.Reason.UNAVAILABLE);
    doThrow(unavailable).when(storage).save(eq(key), anyString(), any());
    assertThatThrownBy(() -> lifecycle.upload(1, 2, InputStream.nullInputStream(), "image/png"))
        .isSameAs(unavailable);
    verify(storage, never()).delete(key);
    doNothing().when(storage).save(eq(key), anyString(), any());
    assertThat(lifecycle.upload(1, 2, InputStream.nullInputStream(), "image/png")).isEqualTo(key);
  }

  @Test
  void leavesUnclaimedImagesAloneAndReportsDeletionsThatNeedRetry() {
    var unclaimed = UUID.randomUUID();
    when(repository.cleanupCandidates(now, 10)).thenReturn(List.of(unclaimed, key));
    when(repository.claimDeletion(key, now)).thenReturn(true);
    doAnswer(call -> {
      call.<Runnable>getArgument(1).run();
      return null;
    }).when(repository).finishDeletion(eq(key), any());
    doThrow(new ImageStorageException(ImageStorageException.Reason.IO)).doNothing().when(storage).delete(key);

    assertThat(lifecycle.cleanExpired(10)).containsExactly(key);
    assertThat(lifecycle.cleanExpired(10)).isEmpty();
    verify(storage, never()).delete(unclaimed);
    verify(storage, times(2)).delete(key);
  }

  @Test
  void defersCleanupAndRejectsExcessUploadsBeforeDatabaseWorkWhileStorageIsBusy() throws Exception {
    var writing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    givenValidatedImage();
    when(repository.reserve(any())).thenReturn(true);
    when(repository.finishUpload(eq(key), eq(now), any())).thenAnswer(call -> {
      writing.countDown();
      assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
      return true;
    });
    when(repository.cleanupCandidates(now, 10)).thenReturn(List.of(key));
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var upload = workers.submit(() -> lifecycle.upload(1, 2, InputStream.nullInputStream(), "image/png"));
      try {
        assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> lifecycle.upload(1, 2, InputStream.nullInputStream(), "image/png"))
            .isInstanceOfSatisfying(ImageStorageException.class,
                error -> assertThat(error.reason()).isEqualTo(ImageStorageException.Reason.UNAVAILABLE));
        assertThat(lifecycle.cleanExpired(10)).isEmpty();
        verify(repository, times(1)).canUpload(1, 2);
        verify(repository, never()).claimDeletion(any(), any());
      } finally {
        release.countDown();
      }
      assertThat(upload.get(5, TimeUnit.SECONDS)).isEqualTo(key);
    }
    lifecycle.cleanExpired(10);
    verify(repository).claimDeletion(key, now);
  }

  private void givenValidatedImage() throws Exception {
    when(repository.canUpload(1, 2)).thenReturn(true);
    when(validator.validate(any(), eq("image/png")))
        .thenReturn(new QuestionImageValidator.ValidatedImage(key, "image/png", 1, 1, new byte[] {1}));
  }
}
