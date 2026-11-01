package com.sahmhoot.user.dto;

import com.sahmhoot.user.entity.Role;
import com.sahmhoot.user.entity.User;

public record UserResponse(Long id, String email, String name, Role role) {

  public static UserResponse from(User u) {
    return new UserResponse(u.getId(), u.getEmail(), u.getName(), u.getRole());
  }
}
