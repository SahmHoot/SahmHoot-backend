package com.sahmhoot.quiz.service.port;

import com.sahmhoot.questionset.service.port.QuestionImageAccess.Metadata;
import java.util.Map;
import java.util.UUID;

public interface RunImageAccess {
  boolean enabled();

  Map<Long, Metadata> runImages(long runId, Long questionId);

  byte[] read(UUID key);
}
