package com.sahmhoot.chat.dto;

import jakarta.validation.constraints.NotBlank;

public record SendChatRequest(@NotBlank String content, Long parentId) {}
