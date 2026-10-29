package com.sahmhoot.questionset.dto;

import com.sahmhoot.questionset.entity.Visibility;
import java.time.Instant;

public record QuestionSetSummaryResponse(Long id, String title, Visibility visibility,
    long questionCount, boolean used, Instant updatedAt) {}
