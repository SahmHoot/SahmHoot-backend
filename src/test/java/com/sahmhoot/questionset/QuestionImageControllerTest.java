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
import com.sahmhoot.questionset.service.QuestionSetService;
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

  @Test
  void servesBytesWithoutAllowingSharedCachingOrSniffing() throws Exception {
    when(auth.requireUser()).thenReturn(user);
    byte[] bytes = {1, 2, 3};
    when(images.download(7, 8, 1)).thenReturn(new QuestionImageService.Download("image/png", bytes));
    mvc.perform(get("/api/question-sets/7/questions/8/image"))
        .andExpect(status().isOk()).andExpect(content().bytes(bytes))
        .andExpect(content().contentType("image/png")).andExpect(header().string("Content-Length", "3"))
        .andExpect(header().string("Cache-Control", "no-store, private"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }

  @Test
  void uses503OnlyForTransientStorageFailures() throws Exception {
    var port = mock(QuestionImageAccess.class);
    var sets = mock(QuestionSetService.class);
    var service = new QuestionImageService(sets, port);
    var api = MockMvcBuilders.standaloneSetup(new QuestionImageController(auth, service))
        .setControllerAdvice(new QuestionSetRestExceptionHandler()).build();
    var key = UUID.randomUUID();
    when(auth.requireUser()).thenReturn(user);
    when(port.enabled()).thenReturn(true);
    when(sets.readableImage(7, 8, user.id())).thenReturn(new QuestionImageAccess.Metadata(key, "image/png", 1, 1));
    when(port.read(key)).thenThrow(new QuestionImageAccess.ImageAccessException(
        QuestionImageAccess.Failure.TEMPORARY_STORAGE));
    api.perform(get("/api/question-sets/7/questions/8/image"))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("IMAGE_STORAGE_UNAVAILABLE"));
    doThrow(new QuestionImageAccess.ImageAccessException(QuestionImageAccess.Failure.STORAGE)).when(port).read(key);
    api.perform(get("/api/question-sets/7/questions/8/image"))
        .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
  }

  private MockMultipartFile file(String name) {
    return new MockMultipartFile(name, "sample.png", "image/png", new byte[] {1, 2, 3});
  }
}
