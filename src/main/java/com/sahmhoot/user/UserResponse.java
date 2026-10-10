package com.sahmhoot.user;

public record UserResponse(Long id, String email, String name, Role role) {

  public static UserResponse from(User u) {
    return new UserResponse(u.getId(), u.getEmail(), u.getName(), u.getRole());
  }
}
