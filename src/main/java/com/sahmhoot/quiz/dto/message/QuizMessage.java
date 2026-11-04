package com.sahmhoot.quiz.dto.message;

import java.time.Instant;

/** 방의 quiz 채널로 보내는 서버 메시지. 엔티티 대신 상태 변경 시점의 값만 담는다. */
public sealed interface QuizMessage permits QuestionOpenedMessage, QuestionClosedMessage,
    QuizFinishedMessage, QuizCanceledMessage, RoomWaitingMessage {

  String type();

  Instant sentAt();
}
