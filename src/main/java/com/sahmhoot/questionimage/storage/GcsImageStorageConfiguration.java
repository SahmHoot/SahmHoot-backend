package com.sahmhoot.questionimage.storage;

import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.StorageOptions;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("prod & !test")
@ConditionalOnProperty(prefix = "app.question-images", name = "enabled", havingValue = "true")
class GcsImageStorageConfiguration {
  @Bean
  ImageStorage imageStorage(
      @Value("${app.question-images.gcs.project-id:${APP_QUESTION_IMAGES_GCS_PROJECT_ID:}}") String projectId,
      @Value("${app.question-images.gcs.bucket:${APP_QUESTION_IMAGES_GCS_BUCKET:}}") String bucket) {
    if (!projectId.matches("[a-z][a-z0-9-]{4,28}[a-z0-9]")
        || !bucket.matches("[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]")) {
      throw new IllegalStateException("GCS image storage requires a valid project ID and bucket name");
    }
    GoogleCredentials credentials;
    try {
      credentials = GoogleCredentials.getApplicationDefault();
    } catch (IOException e) {
      throw new ImageStorageException(ImageStorageException.Reason.AUTHENTICATION);
    }
    if (!(credentials instanceof ComputeEngineCredentials)) {
      throw new ImageStorageException(ImageStorageException.Reason.AUTHENTICATION);
    }
    var options = StorageOptions.newBuilder()
        .setProjectId(projectId)
        .setCredentials(credentials)
        .setTransportOptions(HttpTransportOptions.newBuilder().setConnectTimeout(3_000).setReadTimeout(8_000).build())
        .setRetrySettings(StorageOptions.getDefaultRetrySettings().toBuilder()
            .setMaxAttempts(3)
            .setTotalTimeoutDuration(Duration.ofSeconds(20))
            .build())
        .build();
    return new GcsImageStorage(options.getService(), bucket);
  }
}
