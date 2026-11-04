package com.sahmhoot.room.dto;

import com.sahmhoot.room.entity.RoomStatus;

public record RoomByCodeResponse(Long roomId, String title, String ownerName,
    RoomStatus status, boolean joined, boolean isOwner, String myNickname) {}
