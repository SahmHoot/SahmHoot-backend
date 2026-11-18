package com.sahmhoot.chat.exception;

public class ChatException extends RuntimeException {
  private final ChatErrorCode code;

  public ChatException(ChatErrorCode code) {
    super(code.message());
    this.code = code;
  }

  public ChatErrorCode code() { return code; }
}
