package com.sahmhoot.auth.verification;

import jakarta.mail.Multipart;
import jakarta.mail.Part;

/** multipart/alternative가 중첩된 실제 SMTP 메시지에서 본문을 읽는다. */
final class MimeContent {
  private MimeContent() {}

  static String body(Part part, String mimeType) throws Exception {
    if (part.isMimeType(mimeType)) return (String) part.getContent();
    if (!part.isMimeType("multipart/*")) return null;
    var multipart = (Multipart) part.getContent();
    for (int index = 0; index < multipart.getCount(); index++) {
      String content = body(multipart.getBodyPart(index), mimeType);
      if (content != null) return content;
    }
    return null;
  }
}
