package com.sahmhoot.questionset.dto;

import java.time.Instant;

public record PublicQuestionSetSummaryResponse(Long id, String title, String ownerName,
    long questionCount, Instant updatedAt) {}
