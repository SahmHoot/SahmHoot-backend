package com.sahmhoot.quiz.event;

import com.sahmhoot.quiz.dto.message.QuizMessage;

/** 트랜잭션 안에서 준비하고 커밋 후 전송할 퀴즈 상태 변경 메시지. */
public record QuizStateChangedEvent(Long roomId, QuizMessage message) {}
