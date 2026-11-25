package com.sahmhoot.questionset.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.StringToClassMapItem;
import io.swagger.v3.oas.annotations.media.Schema;
import com.sahmhoot.questionset.entity.Question;
import com.sahmhoot.questionset.service.port.QuestionImageAccess.Metadata;
import java.util.Collections;
import java.util.Map;
import com.sahmhoot.questionset.entity.QuestionType;
import java.util.List;

@Schema(properties = @StringToClassMapItem(key = "image", value = QuestionResponse.Image.class))
public record QuestionResponse(Long id, int orderNo, QuestionType type, String content,
    int timeLimitSeconds, List<ChoiceResponse> choices, @JsonIgnore boolean imagesEnabled,
    @JsonIgnore Image image) {
  @Schema(name = "QuestionImage", nullable = true, types = {"object", "null"}, description = "Omitted when question images are disabled.")
  public record Image(String url, String mimeType, int width, int height) {}

  public QuestionResponse(Long id, int orderNo, QuestionType type, String content, int timeLimitSeconds,
      List<ChoiceResponse> choices) {
    this(id, orderNo, type, content, timeLimitSeconds, choices, false, null);
  }

  @JsonAnyGetter
  public Map<String, Image> imageFields() {
    return imagesEnabled ? Collections.singletonMap("image", image) : Map.of();
  }

  public QuestionResponse withImage(long setId, Metadata metadata) {
    Image view = metadata == null ? null : new Image("/api/question-sets/" + setId + "/questions/" + id + "/image",
        metadata.mimeType(), metadata.width(), metadata.height());
    return new QuestionResponse(id, orderNo, type, content, timeLimitSeconds, choices, true, view);
  }
  public static QuestionResponse from(Question q, List<ChoiceResponse> choices) {
    return new QuestionResponse(q.getId(), q.getOrderNo(), q.getType(), q.getContent(),
        q.getTimeLimitSeconds(), choices);
  }
}
