package com.sahmhoot.room.dto;

import com.sahmhoot.room.entity.RoomStatus;
import java.time.Instant;

public record CreateRoomResponse(
    Long id, String title, RoomStatus status, String joinCode, String joinUrl, Instant createdAt) {}
