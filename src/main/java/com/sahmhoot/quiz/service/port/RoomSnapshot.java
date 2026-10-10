package com.sahmhoot.quiz.service.port;

/**
 * 수업 방 정보 스냅샷.
 * 2번(Room) 도메인과의 연동 인터페이스 전달용 불변 레코드.
 */
public record RoomSnapshot(
    Long id,
    Long hostId,
    RoomStatus status
) {}
