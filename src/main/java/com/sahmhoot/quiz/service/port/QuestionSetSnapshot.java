package com.sahmhoot.quiz.service.port;

/**
 * 문제 세트 정보 스냅샷.
 * 4번(QuestionSet) 도메인과의 연동 인터페이스 전달용 불변 레코드.
 */
public record QuestionSetSnapshot(
    Long id,
    Long hostId
) {}
