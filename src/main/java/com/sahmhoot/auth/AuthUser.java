package com.sahmhoot.auth;

import com.sahmhoot.user.Role;

/**
 * JWT로 확인된 현재 사용자. 컨트롤러에서 {@code @AuthenticationPrincipal AuthUser me}로 받는다.
 *
 * <p>방 안 권한은 role이 아니라 rooms.host_id == userId로 판단한다(06 3절).
 */
public record AuthUser(Long userId, Role role) {}
