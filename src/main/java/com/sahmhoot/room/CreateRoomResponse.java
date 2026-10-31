package com.sahmhoot.room;

import java.time.Instant;

public record CreateRoomResponse(
    Long id, String title, RoomStatus status, String joinCode, String joinUrl, Instant createdAt) {}
