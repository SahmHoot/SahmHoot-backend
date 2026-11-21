package com.sahmhoot.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 비밀번호 찾기 마지막 단계(#108). 비밀번호 규칙은 SignupRequest와 같다. */
public record PasswordResetRequest(
    @NotBlank @Email String email,
    @NotBlank @Pattern(regexp = "\\d{6}", message = "인증 코드는 숫자 6자리입니다.") String code,
    @NotBlank
        @Pattern(
            regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
            message = "비밀번호는 영문·숫자를 포함해 8~64자입니다.")
        String newPassword) {}
