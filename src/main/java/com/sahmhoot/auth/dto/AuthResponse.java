package com.sahmhoot.auth.dto;

import com.sahmhoot.user.UserResponse;
import java.time.Instant;

/** 05-3·05-4 응답: { accessToken, expiresAt, user }. */
public record AuthResponse(String accessToken, Instant expiresAt, UserResponse user) {}
