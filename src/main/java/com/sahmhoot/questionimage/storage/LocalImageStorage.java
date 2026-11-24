package com.sahmhoot.questionimage.storage;

import static com.sahmhoot.questionimage.storage.ImageStorageException.Reason.*;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public final class LocalImageStorage implements ImageStorage {
  private final Path directory;

  public LocalImageStorage(Path directory) {
    try {
      this.directory = Files.createDirectories(directory).toRealPath();
    } catch (IOException e) {
      throw new ImageStorageException(IO);
    }
  }

  @Override
  public void save(UUID key, String mimeType, byte[] content) {
    if (content.length > MAX_BYTES) {
      throw new IllegalArgumentException("Question image exceeds storage size limit");
    }
    Path target = path(key);
    // A deterministic staging path lets deletion recover an interrupted write.
    Path staging = directory.resolve(key + ".part");
    try {
      Files.write(staging, content, StandardOpenOption.CREATE_NEW);
      try {
        // Publishing via a hard link makes the complete file visible without replacing any target.
        Files.createLink(target, staging);
      } finally {
        Files.deleteIfExists(staging);
      }
    } catch (FileAlreadyExistsException e) {
      throw new ImageStorageException(CONFLICT);
    } catch (IOException | UnsupportedOperationException e) {
      throw new ImageStorageException(IO);
    }
  }

  @Override
  public byte[] read(UUID key) {
    try (var stream = Files.newInputStream(path(key), LinkOption.NOFOLLOW_LINKS)) {
      byte[] content = stream.readNBytes(MAX_BYTES + 1);
      if (content.length > MAX_BYTES) {
        throw new ImageStorageException(IO);
      }
      return content;
    } catch (NoSuchFileException e) {
      throw new ImageStorageException(MISSING);
    } catch (IOException e) {
      throw new ImageStorageException(IO);
    }
  }

  @Override
  public void delete(UUID key) {
    try {
      Files.deleteIfExists(path(key));
      Files.deleteIfExists(directory.resolve(key + ".part"));
    } catch (IOException e) {
      throw new ImageStorageException(IO);
    }
  }

  private Path path(UUID key) {
    return directory.resolve(key.toString());
  }
}
