package com.sahmhoot.questionimage.storage;

public class ImageStorageException extends RuntimeException {
  public enum Reason {
    CONFLICT, MISSING, AUTHENTICATION, PERMISSION, UNAVAILABLE, IO
  }

  private final Reason reason;

  public ImageStorageException(Reason reason) {
    super("Image storage operation failed (" + reason + ")");
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
