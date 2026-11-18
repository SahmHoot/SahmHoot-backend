package com.sahmhoot.user;

import com.sahmhoot.chat.service.port.ChatAuthorEmailPort;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatAuthorEmailAdapter implements ChatAuthorEmailPort {
  private final UserRepository users;

  @Override
  public Optional<String> findEmail(Long userId) {
    return users.findById(userId).map(User::getEmail);
  }
}
