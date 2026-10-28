package com.sahmhoot.questionset.auth;

import com.sahmhoot.questionset.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QuestionSetCurrentUserResolver {
  private final QuestionSetUserLookup users;

  public QuestionSetCurrentUser requireUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
      throw new QuestionSetException(QuestionSetErrorCode.UNAUTHORIZED);
    }
    try {
      long id = Long.parseLong(auth.getName());
      if (id <= 0) throw new NumberFormatException();
      return users.findById(id)
          .orElseThrow(() -> new QuestionSetException(QuestionSetErrorCode.UNAUTHORIZED));
    } catch (NumberFormatException e) {
      throw new QuestionSetException(QuestionSetErrorCode.UNAUTHORIZED);
    }
  }
}
