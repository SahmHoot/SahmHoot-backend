package com.sahmhoot.chat.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateChatRestrictionRequest(@NotNull(message = "필수 값입니다.") Boolean chatBlocked) {}
