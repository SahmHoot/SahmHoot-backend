package com.sahmhoot.chat.service.port;

import java.util.Optional;

public interface ChatAuthorEmailPort {
  Optional<String> findEmail(Long userId);
}
