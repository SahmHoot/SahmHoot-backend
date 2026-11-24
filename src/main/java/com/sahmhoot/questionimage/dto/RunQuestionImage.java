package com.sahmhoot.questionimage.dto;

public record RunQuestionImage(String url, String mimeType, int width, int height) {
  public static final RunQuestionImage DISABLED = new RunQuestionImage(null, null, 0, 0);

  public static final class DisabledFilter {
    @Override
    public boolean equals(Object value) {
      return value == DISABLED;
    }

    @Override
    public int hashCode() {
      return DisabledFilter.class.hashCode();
    }
  }
}
