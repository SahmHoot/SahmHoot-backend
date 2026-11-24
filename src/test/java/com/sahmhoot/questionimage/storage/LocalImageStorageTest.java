package com.sahmhoot.questionimage.storage;

import static com.sahmhoot.questionimage.storage.ImageStorageException.Reason.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalImageStorageTest {
  @TempDir Path directory;

  @Test
  void bindsLocalStorageWithoutCloudCredentialsAndExcludesItFromProduction() {
    var runner = new ApplicationContextRunner()
        .withUserConfiguration(LocalImageStorageConfiguration.class)
        .withPropertyValues("app.question-images.local-directory=" + directory);
    runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
        .run(context -> {
          assertThat(context).hasSingleBean(ImageStorage.class);
          var key = UUID.randomUUID();
          context.getBean(ImageStorage.class).save(key, "image/png", new byte[] {1});
          assertThat(directory.resolve(key.toString())).isRegularFile();
        });
    runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "prod"))
        .run(context -> assertThat(context).doesNotHaveBean(ImageStorage.class));
  }

  @Test
  void storesReadsAndDeletesWithoutOverwriting() {
    var storage = new LocalImageStorage(directory);
    var key = UUID.randomUUID();
    byte[] content = {1, 2, 3};
    storage.save(key, "image/png", content);
    assertThatThrownBy(() -> storage.save(key, "image/png", new byte[] {4}))
        .isInstanceOfSatisfying(ImageStorageException.class, error -> assertThat(error.reason()).isEqualTo(CONFLICT));
    assertThat(storage.read(key)).isEqualTo(content);
    storage.delete(key);
    storage.delete(key);
    assertThatThrownBy(() -> storage.read(key))
        .isInstanceOfSatisfying(ImageStorageException.class, error -> assertThat(error.reason()).isEqualTo(MISSING));
  }

  @Test
  void reportsWriteFailureWhenStorageDirectoryIsUnavailable() throws Exception {
    var root = Files.createDirectory(directory.resolve("images"));
    var storage = new LocalImageStorage(root);
    Files.delete(root);
    Files.createFile(root);

    assertThatThrownBy(() -> storage.save(UUID.randomUUID(), "image/png", new byte[] {1}))
        .isInstanceOfSatisfying(ImageStorageException.class, error -> assertThat(error.reason()).isEqualTo(IO));
  }

  @Test
  void reportsFailedDeletionAndAllowsRetry() throws Exception {
    var storage = new LocalImageStorage(directory);
    var key = UUID.randomUUID();
    var target = Files.createDirectory(directory.resolve(key.toString()));
    var child = Files.createFile(target.resolve("child"));
    assertThatThrownBy(() -> storage.delete(key))
        .isInstanceOfSatisfying(ImageStorageException.class, error -> assertThat(error.reason()).isEqualTo(IO));
    Files.delete(child);
    storage.delete(key);
    assertThat(target).doesNotExist();
  }

  @Test
  void recoversAnInterruptedWriteWithoutPublishingPartialContent() throws Exception {
    var storage = new LocalImageStorage(directory);
    var key = UUID.randomUUID();
    var staging = Files.createFile(directory.resolve(key + ".part"));
    assertThatThrownBy(() -> storage.read(key)).isInstanceOf(ImageStorageException.class);
    assertThatThrownBy(() -> storage.save(key, "image/png", new byte[] {1}))
        .isInstanceOfSatisfying(ImageStorageException.class, error -> assertThat(error.reason()).isEqualTo(CONFLICT));
    storage.delete(key);
    assertThat(staging).doesNotExist();
  }
}
