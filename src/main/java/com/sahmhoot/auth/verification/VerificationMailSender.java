package com.sahmhoot.auth.verification;

import com.sahmhoot.common.error.ApiException;
import com.sahmhoot.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 인증 코드 메일 발송. spring.mail.host가 비어 있으면(로컬 개발) 메일 대신 로그에 코드를 찍는다.
 */
@Slf4j
@Component
public class VerificationMailSender {

  private final ObjectProvider<JavaMailSender> mailSender;
  private final String host;
  private final String from;

  public VerificationMailSender(
      ObjectProvider<JavaMailSender> mailSender,
      @Value("${spring.mail.host:}") String host,
      @Value("${app.mail.from:no-reply@sahmhoot.local}") String from) {
    this.mailSender = mailSender;
    this.host = host;
    this.from = from;
  }

  public void send(String email, String code) {
    JavaMailSender sender = mailSender.getIfAvailable();
    if (!StringUtils.hasText(host) || sender == null) {
      log.info("[개발용] 메일 서버 미설정 — {} 인증 코드: {}", email, code);
      return;
    }
    SimpleMailMessage msg = new SimpleMailMessage();
    msg.setFrom(from);
    msg.setTo(email);
    msg.setSubject("[삼훗] 이메일 인증 코드");
    msg.setText("인증 코드: " + code + "\n10분 안에 입력해 주세요.");
    try {
      sender.send(msg);
    } catch (MailException e) {
      log.warn("인증 메일 발송 실패: {}", email, e);
      throw new ApiException(ErrorCode.MAIL_SEND_FAILED);
    }
  }
}
