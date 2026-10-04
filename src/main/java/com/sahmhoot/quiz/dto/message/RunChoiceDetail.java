package com.sahmhoot.quiz.dto.message;

/**
 * 실시간 문항 공개 시 전송되는 선택지 요약 DTO (정답 정보 제외).
 */
public record RunChoiceDetail(
    Long id,
    int orderNo,
    String content
) {}
