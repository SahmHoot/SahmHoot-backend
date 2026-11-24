package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahmhoot.questionimage.dto.RunQuestionImage;
import com.sahmhoot.quiz.dto.QuizResultResponse;
import com.sahmhoot.quiz.dto.message.QuestionOpenedMessage;
import com.sahmhoot.quiz.entity.QuestionType;
import com.sahmhoot.room.dto.RoomStateResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RunQuestionImageJsonTest {
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void stateAndRealtimeDistinguishDisabledMissingAndPresentImages() {
    assertThat(payloads(RunQuestionImage.DISABLED)).allMatch(node -> !node.has("image"));
    assertThat(payloads(null)).allMatch(node -> node.has("image") && node.get("image").isNull());
    var image = new RunQuestionImage("/api/rooms/1/quiz-runs/2/questions/3/image", "image/png", 2, 4);
    assertThat(payloads(image)).allSatisfy(node -> assertThat(node.get("image")).isEqualTo(json.valueToTree(image)));
  }

  private List<JsonNode> payloads(RunQuestionImage image) {
    var now = Instant.EPOCH;
    return List.of(
        json.valueToTree(QuestionOpenedMessage.of(2L, 3L, 1, 1, QuestionType.TRUE_FALSE, "test",
            10, now, List.of(), now, image)),
        json.valueToTree(new QuizResultResponse.QuestionResultDetail(3L, 1, "test", 4L, 0, 0, 0,
            List.of(), image)),
        json.valueToTree(new RoomStateResponse.CurrentQuestion(3L, 1, "TRUE_FALSE", 10, "test",
            List.of(), now, now, null, null, image)),
        json.valueToTree(new RoomStateResponse.QuestionResult(3L, 1, "test", 4L, 0, 0, 0, List.of(), image)));
  }
}
