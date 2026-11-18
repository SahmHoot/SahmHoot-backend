package com.sahmhoot.auth.verification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.auth.exception.ApiException;
import com.sahmhoot.auth.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(OutputCaptureExtension.class)
class VerificationMailSenderTest {
  private final JavaMailSender transport = mock(JavaMailSender.class);
  @SuppressWarnings("unchecked")
  private final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);

  @Test void sendsCodeOnlyThroughConfiguredTransport() {
    when(provider.getIfAvailable()).thenReturn(transport);
    var sender = new VerificationMailSender(provider, "127.0.0.1", "sender@test.invalid");
    sender.send("recipient@test.invalid", "123456");
    var captured = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(transport).send(captured.capture());
    assertThat(captured.getValue().getFrom()).isEqualTo("sender@test.invalid");
    assertThat(captured.getValue().getTo()).containsExactly("recipient@test.invalid");
    assertThat(captured.getValue().getSubject()).isEqualTo("[삼훗] 이메일 인증 코드");
    assertThat(captured.getValue().getText()).contains("123456", "10분");
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
        .when(transport).send(any(SimpleMailMessage.class));
    assertFailure(new VerificationMailSender(provider, "127.0.0.1", "sender@test.invalid"));
    assertThat(output.getAll()).doesNotContain("123456", "private-value", "recipient@test.invalid");
  }

  private void assertFailure(VerificationMailSender sender) {
    assertThatThrownBy(() -> sender.send("recipient@test.invalid", "123456"))
        .isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.MAIL_SEND_FAILED));
  }
}
