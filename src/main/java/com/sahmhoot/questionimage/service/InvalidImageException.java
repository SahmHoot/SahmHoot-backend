package com.sahmhoot.questionimage.service;

public class InvalidImageException extends RuntimeException {
  public InvalidImageException() {
    super("Invalid question image");
  }
}
