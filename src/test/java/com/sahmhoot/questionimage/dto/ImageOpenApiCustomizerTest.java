package com.sahmhoot.questionimage.dto;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImageOpenApiCustomizerTest {
  @Test
  void allowsExplicitNullWithoutMakingImagesRequired() {
    var image = new Schema<>().$ref("#/components/schemas/QuestionImage");
    var question = new Schema<>();
    question.addProperty("image", image);
    var unrelated = new Schema<>();
    unrelated.addProperty("image", new Schema<>().type("string"));
    var document = new OpenAPI().components(new Components().schemas(Map.of(
        "QuestionResponse", question, "OtherResponse", unrelated)));
    new ImageOpenApiCustomizer().customise(document);
    Schema<?> property = question.getProperties().get("image");
    assertThat(property.getAnyOf()).hasSize(2);
    assertThat(property.getAnyOf().getFirst()).isSameAs(image);
    Schema<?> nullOption = property.getAnyOf().getLast();
    assertThat(nullOption.getTypes()).containsExactly("null");
    assertThat(question.getRequired()).isNullOrEmpty();
    assertThat(unrelated.getProperties().get("image").getType()).isEqualTo("string");
  }
}
