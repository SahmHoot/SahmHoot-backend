package com.sahmhoot.websocket;

import com.sahmhoot.auth.AuthUser;
import java.security.Principal;

/** STOMP 세션의 사용자. 이름 = userId 문자열(06 1절). /user/queue/** 전달에 이 이름을 쓴다. */
public record StompPrincipal(AuthUser user) implements Principal {

  @Override
  public String getName() {
    return String.valueOf(user.userId());
  }
}
