package com.sahmhoot.questionset.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sahmhoot.questionset.entity.Visibility;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;

/**
 * PATCH needs field presence as well as value; a missing description must not erase it.
 * Setters are used only by JSON binding.
 */
@Getter
public class UpdateQuestionSetRequest {
  @Size(min = 1, max = 50)
  @Pattern(regexp = "(?s).*\\S.*")
  private String title;
  private String description;
  private Visibility visibility;
  @JsonIgnore private boolean titlePresent;
  @JsonIgnore private boolean descriptionPresent;
  @JsonIgnore private boolean visibilityPresent;

  @JsonSetter("title")
  public void readTitle(String value) { titlePresent = true; title = value; }
  @JsonSetter("description")
  public void readDescription(String value) { descriptionPresent = true; description = value; }
  @JsonSetter("visibility")
  public void readVisibility(Visibility value) { visibilityPresent = true; visibility = value; }

  @JsonIgnore
  @AssertTrue(message = "title은 null일 수 없습니다.")
  public boolean isTitleValueValid() { return !titlePresent || title != null; }

  @JsonIgnore
  @AssertTrue(message = "visibility는 null일 수 없습니다.")
  public boolean isVisibilityValueValid() { return !visibilityPresent || visibility != null; }
}
