package com.sahmhoot.questionimage.storage;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.sahmhoot.questionimage.storage.ImageStorageException.Reason;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.Map;
import java.util.UUID;

public final class GcsImageStorage implements ImageStorage {
  private static final String WRITE_ID = "sahmhoot-write-id";
  private final Storage storage;
  private final String bucket;

  public GcsImageStorage(Storage storage, String bucket) {
    this.storage = storage;
    this.bucket = bucket;
  }

  @Override
  public void save(UUID key, String mimeType, byte[] content) {
    if (content.length > MAX_BYTES) {
      throw new IllegalArgumentException("Question image exceeds storage size limit");
    }
    String writeId = UUID.randomUUID().toString();
    try {
      storage.create(BlobInfo.newBuilder(id(key)).setContentType(mimeType)
              .setMetadata(Map.of(WRITE_ID, writeId)).build(), content,
          Storage.BlobTargetOption.doesNotExist());
    } catch (StorageException e) {
      if (e.getCode() != 412) {
        throw failure(e, Reason.IO);
      }
      resolveWriteConflict(key, writeId);
    }
  }

  private void resolveWriteConflict(UUID key, String writeId) {
    // The SDK may retry a successful create whose response was lost.
    Blob existing;
    try {
      existing = storage.get(id(key));
    } catch (StorageException e) {
      throw failure(e, Reason.IO);
    }
    if (existing == null) {
      throw new ImageStorageException(Reason.UNAVAILABLE);
    }
    if (existing.getMetadata() == null || !writeId.equals(existing.getMetadata().get(WRITE_ID))) {
      throw new ImageStorageException(Reason.CONFLICT);
    }
  }

  @Override
  public byte[] read(UUID key) {
    try {
      Blob blob = storage.get(id(key));
      if (blob == null) {
        throw new ImageStorageException(Reason.MISSING);
      }
      if (blob.getSize() > MAX_BYTES) {
        throw new ImageStorageException(Reason.IO);
      }
      try (var input = Channels.newInputStream(storage.reader(blob.getBlobId()))) {
        byte[] content = input.readNBytes(MAX_BYTES + 1);
        if (content.length > MAX_BYTES) {
          throw new ImageStorageException(Reason.IO);
        }
        return content;
      } catch (IOException e) {
        throw new ImageStorageException(Reason.UNAVAILABLE);
      }
    } catch (StorageException e) {
      throw failure(e, Reason.MISSING);
    }
  }

  @Override
  public void delete(UUID key) {
    try {
      Blob blob = storage.get(id(key));
      if (blob != null) {
        storage.delete(blob.getBlobId(), Storage.BlobSourceOption.generationMatch());
      }
    } catch (StorageException e) {
      if (e.getCode() != 404) {
        throw failure(e, Reason.IO);
      }
    }
  }

  private BlobId id(UUID key) {
    return BlobId.of(bucket, "question-images/" + key);
  }

  private ImageStorageException failure(StorageException error, Reason notFound) {
    return new ImageStorageException(switch (error.getCode()) {
      case 401 -> Reason.AUTHENTICATION;
      case 403 -> Reason.PERMISSION;
      case 404 -> notFound;
      case 0, 408, 429, 500, 502, 503, 504 -> Reason.UNAVAILABLE;
      default -> Reason.IO;
    });
  }
}
