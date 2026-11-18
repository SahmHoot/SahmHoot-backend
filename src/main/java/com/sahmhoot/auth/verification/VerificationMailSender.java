package com.sahmhoot.auth.verification;

import com.sahmhoot.auth.exception.ApiException;
import com.sahmhoot.auth.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 인증 코드는 SMTP로만 전달한다. 설정 누락도 발송 실패로 처리한다.
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
      @Value("${app.mail.from:}") String from) {
    this.mailSender = mailSender;
    this.host = host;
    this.from = from;
  }

  public void send(String email, String code) {
    JavaMailSender sender = mailSender.getIfAvailable();
    if (!StringUtils.hasText(host) || sender == null || !StringUtils.hasText(from)) {
      throw new ApiException(ErrorCode.MAIL_SEND_FAILED);
    }
    SimpleMailMessage msg = new SimpleMailMessage();
    msg.setFrom(from);
    msg.setTo(email);
    msg.setSubject("[삼훗] 이메일 인증 코드");
    msg.setText("인증 코드: " + code + "\n10분 안에 입력해 주세요.");
    try {
      sender.send(msg);
    } catch (MailException e) {
      // SMTP 예외에는 수신자·본문·인증 정보가 포함될 수 있어 원문은 기록하지 않는다.
      log.warn("인증 메일 발송 실패: {}", e.getClass().getSimpleName());
      throw new ApiException(ErrorCode.MAIL_SEND_FAILED);
    }
  }
}
