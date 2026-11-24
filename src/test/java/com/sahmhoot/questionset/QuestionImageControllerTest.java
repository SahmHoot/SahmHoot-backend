package com.sahmhoot.questionset;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.questionset.auth.QuestionSetCurrentUser;
import com.sahmhoot.questionset.auth.QuestionSetCurrentUserResolver;
import com.sahmhoot.questionset.controller.QuestionImageController;
import com.sahmhoot.questionset.exception.QuestionSetRestExceptionHandler;
import com.sahmhoot.questionset.service.QuestionImageService;
import com.sahmhoot.questionset.service.port.QuestionImageAccess;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QuestionImageControllerTest {
  private final QuestionSetCurrentUserResolver auth = mock(QuestionSetCurrentUserResolver.class);
  private final QuestionImageService images = mock(QuestionImageService.class);
  private final QuestionSetCurrentUser user = new QuestionSetCurrentUser(1L, "PROFESSOR", "Professor");
  private final org.springframework.test.web.servlet.MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new QuestionImageController(auth, images))
          .setControllerAdvice(new QuestionSetRestExceptionHandler()).build();

  @Test
  void uploadsExactlyOnePartAndReturnsMetadata() throws Exception {
    when(auth.requireUser()).thenReturn(user);
    UUID key = UUID.randomUUID();
    when(images.upload(eq(7L), eq(user), any(), eq("image/png"))).thenReturn(
        new QuestionImageAccess.Upload(key, "image/png", 3, 1, 1, Instant.parse("2026-11-25T00:00:00Z")));
    mvc.perform(multipart("/api/question-sets/7/images").file(file("file")))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.imageRef").value(key.toString()))
        .andExpect(jsonPath("$.sizeBytes").value(3));
  }

  @Test
  void rejectsMultipleFiles() throws Exception {
    when(auth.requireUser()).thenReturn(user);
    mvc.perform(multipart("/api/question-sets/7/images").file(file("file")).file(file("file")))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    verifyNoInteractions(images);
  }

  private MockMultipartFile file(String name) {
    return new MockMultipartFile(name, "sample.png", "image/png", new byte[] {1, 2, 3});
  }
}
