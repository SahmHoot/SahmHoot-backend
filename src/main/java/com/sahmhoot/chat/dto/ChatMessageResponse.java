package com.sahmhoot.chat.dto;

import java.time.Instant;

public record ChatMessageResponse(Long id, Long senderParticipantId, String nickname,
    boolean isOwner, String content, Long parentId, String parentPreview, Instant createdAt) {}
