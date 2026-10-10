package com.sahmhoot.user;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.common.error.ApiException;
import com.sahmhoot.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

  private final UserRepository userRepository;

  /** 05-5 내 정보. */
  @GetMapping("/me")
  public UserResponse me(@AuthenticationPrincipal AuthUser authUser) {
    return userRepository
        .findById(authUser.userId())
        .map(UserResponse::from)
        .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
  }
}
