package com.sahmhoot.auth;

import com.sahmhoot.auth.dto.AuthResponse;
import com.sahmhoot.auth.dto.EmailConfirmRequest;
import com.sahmhoot.auth.dto.EmailRequest;
import com.sahmhoot.auth.dto.LoginRequest;
import com.sahmhoot.auth.dto.PasswordResetRequest;
import com.sahmhoot.auth.dto.SignupRequest;
import com.sahmhoot.auth.verification.EmailVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 05 API 1~4번과 비밀번호 찾기(#108). 모두 공개. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final EmailVerificationService verificationService;
  private final AuthService authService;

  @PostMapping("/email-verifications")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void sendCode(@Valid @RequestBody EmailRequest req) {
    verificationService.send(req.email());
  }

  @PostMapping("/email-verifications/confirm")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void confirmCode(@Valid @RequestBody EmailConfirmRequest req) {
    verificationService.confirm(req.email(), req.code());
  }

  @PostMapping("/signup")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse signup(@Valid @RequestBody SignupRequest req) {
    return authService.signup(req);
  }

  @PostMapping("/login")
  public AuthResponse login(@Valid @RequestBody LoginRequest req) {
    return authService.login(req);
  }

  /** 비밀번호 찾기 코드 발송(#108). 가입되지 않은 이메일도 같은 204를 돌려준다. */
  @PostMapping("/password-reset/email-verifications")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void sendPasswordResetCode(@Valid @RequestBody EmailRequest req) {
    verificationService.sendPasswordReset(req.email());
  }

  @PostMapping("/password-reset/email-verifications/confirm")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void confirmPasswordResetCode(@Valid @RequestBody EmailConfirmRequest req) {
    verificationService.confirmPasswordReset(req.email(), req.code());
  }

  @PostMapping("/password-reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resetPassword(@Valid @RequestBody PasswordResetRequest req) {
    authService.resetPassword(req);
  }
}
