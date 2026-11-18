package com.sahmhoot.auth.verification;

import com.sahmhoot.auth.exception.ApiException;
import com.sahmhoot.auth.exception.ErrorCode;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

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
    try {
      var message = sender.createMimeMessage();
      var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
      helper.setFrom(from, "SahmHoot");
      helper.setTo(email);
      helper.setSubject("[SahmHoot!] 이메일 인증번호 발송");
      String plainText = "SahmHoot!\n\n인증번호: " + code
          + "\n\n인증번호는 10분 동안 유효합니다.\n본인이 요청하지 않았다면 이 메일을 무시해 주세요.";
      String html = new ClassPathResource("mail/verification-email.html").getContentAsString(StandardCharsets.UTF_8)
          .replace("{{verificationCode}}", HtmlUtils.htmlEscape(code));
      helper.setText(plainText, html);
      sender.send(message);
    } catch (MailException | MessagingException | IOException e) {
      // SMTP 예외에는 수신자·본문·인증 정보가 포함될 수 있어 원문은 기록하지 않는다.
      log.warn("인증 메일 발송 실패: {}", e.getClass().getSimpleName());
      throw new ApiException(ErrorCode.MAIL_SEND_FAILED);
    }
  }
}
