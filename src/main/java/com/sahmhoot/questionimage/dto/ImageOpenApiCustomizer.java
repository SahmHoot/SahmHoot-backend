package com.sahmhoot.questionimage.dto;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

@Component
class ImageOpenApiCustomizer implements OpenApiCustomizer {
  @Override
  public void customise(OpenAPI document) {
    if (document.getComponents() == null || document.getComponents().getSchemas() == null) return;
    var schemas = document.getComponents().getSchemas();
    for (String model : List.of("QuestionResponse", "CurrentQuestion", "QuestionResult")) {
      Schema<?> schema = schemas.get(model);
      if (schema == null || schema.getProperties() == null) continue;
      Schema<?> image = schema.getProperties().get("image");
      if (image != null && image.get$ref() != null) {
        schema.addProperty("image", new ComposedSchema().addAnyOfItem(image)
            .addAnyOfItem(new Schema<>().types(Set.of("null"))));
      }
    }
  }
}
