package com.sahmhoot.questionimage.storage;

import java.util.UUID;

public interface ImageStorage {
  int MAX_BYTES = 5_242_880;

  void save(UUID key, String mimeType, byte[] content);

  byte[] read(UUID key);

  void delete(UUID key);
}
