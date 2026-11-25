package com.sahmhoot.questionimage.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.sahmhoot.questionimage.storage.ImageStorageException.Reason;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GcsImageStorageTest {
  private final UUID key = UUID.randomUUID();
  private final BlobId id = BlobId.of("test-bucket", "question-images/" + key);
  private final BlobId generation = BlobId.of(id.getBucket(), id.getName(), 42L);
  private final Storage sdk = mock(Storage.class);
  private final GcsImageStorage storage = new GcsImageStorage(sdk, id.getBucket());

  @Test
  void writesOnlyIfMissingAndReadsAndDeletesTheObservedGeneration() throws IOException {
    byte[] content = {1, 2};
    storage.save(key, "image/png", content);
    verify(sdk).create(argThat(blob -> blob.getBlobId().equals(id) && blob.getContentType().equals("image/png")),
        eq(content), eq(Storage.BlobTargetOption.doesNotExist()));
    stubObject();
    ReadChannel reader = mock(ReadChannel.class);
    when(sdk.reader(generation)).thenReturn(reader);
    when(reader.read(any(ByteBuffer.class))).thenAnswer(call -> {
      call.<ByteBuffer>getArgument(0).put(content);
      return content.length;
    }).thenReturn(-1);
    assertThat(storage.read(key)).isEqualTo(content);
    storage.delete(key);
    verify(sdk).delete(generation, Storage.BlobSourceOption.generationMatch());
  }

  @Test
  void treatsMissingObjectsAsMissingForReadAndSuccessForDelete() {
    assertThatThrownBy(() -> storage.read(key))
        .isInstanceOfSatisfying(ImageStorageException.class, e -> assertThat(e.reason()).isEqualTo(Reason.MISSING));
    storage.delete(key);
    verify(sdk, never()).delete(any(BlobId.class), any(Storage.BlobSourceOption.class));
    stubObject();
    when(sdk.delete(generation, Storage.BlobSourceOption.generationMatch()))
        .thenThrow(new StorageException(404, "missing"));
    storage.delete(key);
  }

  @Test
  void distinguishesARealCollisionFromAnAlreadySuccessfulWrite() {
    Blob existing = stubObject();
    when(existing.getMetadata()).thenReturn(Map.of());
    when(sdk.create(any(BlobInfo.class), any(byte[].class), eq(Storage.BlobTargetOption.doesNotExist())))
        .thenThrow(new StorageException(412, "precondition"))
        .thenAnswer(call -> {
          var written = call.<BlobInfo>getArgument(0);
          when(existing.getMetadata()).thenReturn(written.getMetadata());
          throw new StorageException(412, "precondition after retry");
        });
    assertThatThrownBy(() -> storage.save(key, "image/png", new byte[] {1}))
        .isInstanceOfSatisfying(ImageStorageException.class, e -> assertThat(e.reason()).isEqualTo(Reason.CONFLICT));
    storage.save(key, "image/png", new byte[] {1});
  }

  @Test
  void treatsAMissingUploadBucketAsStorageFailure() {
    when(sdk.create(any(BlobInfo.class), any(byte[].class), eq(Storage.BlobTargetOption.doesNotExist())))
        .thenThrow(new StorageException(404, "missing bucket"));
    assertThatThrownBy(() -> storage.save(key, "image/png", new byte[] {1}))
        .isInstanceOfSatisfying(ImageStorageException.class, e -> assertThat(e.reason()).isEqualTo(Reason.IO));
  }

  @ParameterizedTest
  @CsvSource({"401,AUTHENTICATION", "403,PERMISSION", "408,UNAVAILABLE", "503,UNAVAILABLE", "0,UNAVAILABLE"})
  void mapsSdkFailuresWithoutExposingProviderMessages(int code, Reason reason) {
    when(sdk.get(id)).thenThrow(new StorageException(code, "provider details"));
    assertThatThrownBy(() -> storage.read(key)).isInstanceOfSatisfying(ImageStorageException.class, e -> {
      assertThat(e.reason()).isEqualTo(reason);
      assertThat(e.getMessage()).doesNotContain("provider details");
    });
  }

  @Test
  void productionRequiresGcsSettingsEvenIfLocalIsAlsoActive() {
    new ApplicationContextRunner()
        .withUserConfiguration(GcsImageStorageConfiguration.class, LocalImageStorageConfiguration.class)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "prod"))
        .withPropertyValues("app.question-images.enabled=true")
        .run(context -> assertThat(context).hasFailed().getFailure()
            .hasRootCauseMessage("GCS image storage requires a valid project ID and bucket name"));
  }

  @Test
  void productionBindsTheRealSdkWithSimulatedVmAdc() {
    var credentials = ComputeEngineCredentials.create();
    try (var auth = mockStatic(GoogleCredentials.class)) {
      auth.when(GoogleCredentials::getApplicationDefault).thenReturn(credentials);
      new ApplicationContextRunner()
          .withUserConfiguration(GcsImageStorageConfiguration.class, LocalImageStorageConfiguration.class)
          .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", "local"))
          .withPropertyValues("app.question-images.enabled=true", "APP_QUESTION_IMAGES_GCS_PROJECT_ID=example-project",
              "APP_QUESTION_IMAGES_GCS_BUCKET=example-image-bucket")
          .run(context -> {
            assertThat(context).hasSingleBean(ImageStorage.class);
            assertThat(context.getBean(ImageStorage.class)).isInstanceOf(GcsImageStorage.class);
          });
      auth.verify(GoogleCredentials::getApplicationDefault);
    }
  }

  @Test
  void enabledProductionRejectsUnavailableAdc() {
    try (var auth = mockStatic(GoogleCredentials.class)) {
      auth.when(GoogleCredentials::getApplicationDefault).thenThrow(new IOException("no metadata service"));
      new ApplicationContextRunner()
          .withUserConfiguration(GcsImageStorageConfiguration.class, LocalImageStorageConfiguration.class)
          .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", "local"))
          .withPropertyValues("app.question-images.enabled=true",
              "app.question-images.gcs.project-id=example-project", "app.question-images.gcs.bucket=example-bucket")
          .run(context -> assertThat(context).hasFailed().getFailure()
              .hasRootCauseMessage("Image storage operation failed (AUTHENTICATION)"));
    }
  }

  @Test
  void rejectsOversizedWritesBeforeCallingTheSdkAndOversizedObjectsBeforeDownloading() {
    assertThatIllegalArgumentException().isThrownBy(
        () -> storage.save(key, "image/png", new byte[ImageStorage.MAX_BYTES + 1]));
    verifyNoInteractions(sdk);
    Blob blob = stubObject();
    when(blob.getSize()).thenReturn((long) ImageStorage.MAX_BYTES + 1);
    assertThatThrownBy(() -> storage.read(key)).isInstanceOf(ImageStorageException.class);
    verify(sdk, never()).reader(generation);
  }

  private Blob stubObject() {
    Blob blob = mock(Blob.class);
    when(blob.getBlobId()).thenReturn(generation);
    when(blob.getSize()).thenReturn(2L);
    when(sdk.get(id)).thenReturn(blob);
    return blob;
  }
}
