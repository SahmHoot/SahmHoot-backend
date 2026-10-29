package com.sahmhoot.questionset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.questionset.auth.*;
import com.sahmhoot.questionset.exception.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class QuestionSetCurrentUserResolverTest {
  private final QuestionSetUserLookup users = mock(QuestionSetUserLookup.class);
  private final QuestionSetCurrentUserResolver resolver = new QuestionSetCurrentUserResolver(users);

  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void missingAndAnonymousAuthenticationAreRejected() {
    assertUnauthorized();
    SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
        "test", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    assertUnauthorized();
    verifyNoInteractions(users);
  }

  @ParameterizedTest @ValueSource(strings = {"anonymousUser", "0", "-1", "9223372036854775808"})
  void invalidUserIdIsRejected(String name) {
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(name, null, List.of()));
    assertUnauthorized();
    verifyNoInteractions(users);
  }

  @Test void unauthenticatedTokenIsRejected() {
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.unauthenticated("42", null));
    assertUnauthorized();
  }

  @Test void userMustExistAndRoleComesFromDatabase() {
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated("42", null,
            List.of(new SimpleGrantedAuthority("ROLE_PROFESSOR"))));
    when(users.findById(42L)).thenReturn(Optional.empty());
    assertUnauthorized();
    var student = new QuestionSetCurrentUser(42L, "STUDENT", "학생");
    when(users.findById(42L)).thenReturn(Optional.of(student));
    assertThat(resolver.requireUser()).isEqualTo(student);
    assertThat(resolver.requireUser().professor()).isFalse();
  }

  private void assertUnauthorized() {
    assertThatThrownBy(resolver::requireUser).isInstanceOfSatisfying(QuestionSetException.class,
        e -> assertThat(e.getCode()).isEqualTo(QuestionSetErrorCode.UNAUTHORIZED));
  }
}
