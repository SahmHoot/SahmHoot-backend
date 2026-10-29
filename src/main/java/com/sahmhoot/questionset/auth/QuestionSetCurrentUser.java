package com.sahmhoot.questionset.auth;

public record QuestionSetCurrentUser(Long id, String role, String name) {
  public boolean professor() { return "PROFESSOR".equals(role); }
}
