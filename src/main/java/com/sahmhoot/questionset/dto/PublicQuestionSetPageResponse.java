package com.sahmhoot.questionset.dto;

import java.util.List;

public record PublicQuestionSetPageResponse(List<PublicQuestionSetSummaryResponse> content,
    int page, int size, long totalElements, long totalPages) {}
