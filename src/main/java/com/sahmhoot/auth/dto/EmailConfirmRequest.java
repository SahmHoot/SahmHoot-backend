package com.sahmhoot.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record EmailConfirmRequest(
    @NotBlank @Email String email,
    @NotBlank @Pattern(regexp = "\\d{6}", message = "인증 코드는 숫자 6자리입니다.") String code) {}
