package com.sahmhoot.reaction.exception;

public class ReactionException extends RuntimeException {
  private final ReactionErrorCode code;

  public ReactionException(ReactionErrorCode code) {
    super(code.message());
    this.code = code;
  }

  public ReactionErrorCode code() {
    return code;
  }
}
