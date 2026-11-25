package com.sahmhoot.questionimage.service;

public class InvalidImageException extends RuntimeException {
  public enum Reason { INVALID, DIMENSIONS, TOO_LARGE, UNSUPPORTED }

  private final Reason reason;

  public InvalidImageException() {
    this(Reason.INVALID);
  }

  public InvalidImageException(Reason reason) {
    super("Invalid question image");
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
