package com.sahmhoot.auth.verification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.auth.exception.ApiException;
import com.sahmhoot.auth.exception.ErrorCode;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(OutputCaptureExtension.class)
class VerificationMailSenderTest {
  private final JavaMailSender transport = mock(JavaMailSender.class);
  @SuppressWarnings("unchecked")
  private final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);

  @BeforeEach void setup() {
    when(transport.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
  }

  @Test void sendsCodeOnlyThroughConfiguredTransport() throws Exception {
    when(provider.getIfAvailable()).thenReturn(transport);
    var sender = new VerificationMailSender(provider, "127.0.0.1", "sender@test.invalid");
    sender.send("recipient@test.invalid", "123456");
    var captured = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
    verify(transport).send(captured.capture());
    var message = captured.getValue();
    message.saveChanges();
    assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("sender@test.invalid");
    assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("SahmHoot");
    assertThat(message.getAllRecipients()[0].toString()).isEqualTo("recipient@test.invalid");
    assertThat(message.getSubject()).isEqualTo("[SahmHoot!] 이메일 인증번호 발송");
    assertThat(MimeContent.body(message, "text/plain")).contains("123456", "10분", "수업 중 실시간 참여형 플랫폼");
    assertThat(MimeContent.body(message, "text/html"))
        .contains("123456", "SahmHoot!", "수업 중 실시간 참여형 플랫폼", "#2538d2", "#6577f0", "All rights reserved.")
        .doesNotContain("{{verificationCode}}", "이메일을 인증해 주세요", "삼훗 가입을 위해");
  }

  @Test void passwordResetMailHasItsOwnSubjectAndIntro() throws Exception {
    when(provider.getIfAvailable()).thenReturn(transport);
    var sender = new VerificationMailSender(provider, "127.0.0.1", "sender@test.invalid");
    sender.sendPasswordReset("recipient@test.invalid", "654321");
    var captured = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
    verify(transport).send(captured.capture());
    var message = captured.getValue();
    message.saveChanges();
    assertThat(message.getSubject()).isEqualTo("[SahmHoot!] 비밀번호 재설정 인증번호");
    assertThat(MimeContent.body(message, "text/plain")).contains("비밀번호 재설정 요청", "654321", "10분");
    assertThat(MimeContent.body(message, "text/html"))
        .contains("654321", "<title>[SahmHoot!] 비밀번호 재설정 인증번호</title>")
        .doesNotContain("{{verificationCode}}", "이메일 인증번호 발송");
  }

  @Test void missingSettingsFailWithoutPrintingCode(CapturedOutput output) {
    when(provider.getIfAvailable()).thenReturn(transport);
    assertFailure(new VerificationMailSender(provider, "", "sender@test.invalid"));
    assertFailure(new VerificationMailSender(provider, "127.0.0.1", " "));
    when(provider.getIfAvailable()).thenReturn(null);
    assertFailure(new VerificationMailSender(provider, "127.0.0.1", "sender@test.invalid"));
    verifyNoInteractions(transport);
    assertThat(output.getAll()).doesNotContain("123456", "recipient@test.invalid");
  }

  @Test void transportFailureDoesNotExposeSmtpExceptionContents(CapturedOutput output) {
    when(provider.getIfAvailable()).thenReturn(transport);
    doThrow(new MailSendException("SMTP response contains code=123456 and credential=private-value"))
        .when(transport).send(any(MimeMessage.class));
    assertFailure(new VerificationMailSender(provider, "127.0.0.1", "sender@test.invalid"));
    assertThat(output.getAll()).doesNotContain("123456", "private-value", "recipient@test.invalid");
  }

  @Test void dynamicContentIsEscapedInHtml() throws Exception {
    when(provider.getIfAvailable()).thenReturn(transport);
    new VerificationMailSender(provider, "127.0.0.1", "sender@test.invalid")
        .send("recipient@test.invalid", "<b>code</b>");
    var captured = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
    verify(transport).send(captured.capture());
    captured.getValue().saveChanges();
    assertThat(MimeContent.body(captured.getValue(), "text/html"))
        .contains("&lt;b&gt;code&lt;/b&gt;").doesNotContain("<b>code</b>");
  }

  private void assertFailure(VerificationMailSender sender) {
    assertThatThrownBy(() -> sender.send("recipient@test.invalid", "123456"))
        .isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.MAIL_SEND_FAILED));
  }
}
