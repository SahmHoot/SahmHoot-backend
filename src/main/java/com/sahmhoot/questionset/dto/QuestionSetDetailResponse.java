package com.sahmhoot.questionset.dto;

import com.sahmhoot.questionset.entity.Visibility;
import java.util.List;

public record QuestionSetDetailResponse(Long id, String title, Visibility visibility,
    String description, String ownerName, List<QuestionResponse> questions) {}
