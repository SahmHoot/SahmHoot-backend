package com.sahmhoot.room.exception;

public class RoomException extends RuntimeException {
  private final RoomErrorCode errorCode;

  public RoomException(RoomErrorCode errorCode) {
    super(errorCode.defaultMessage());
    this.errorCode = errorCode;
  }

  public RoomErrorCode getErrorCode() {
    return errorCode;
  }
}
