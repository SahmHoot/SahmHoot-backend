package com.sahmhoot.room;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(
    @NotBlank(message = "수업명을 입력해 주세요.")
    @Size(max = 50, message = "수업명은 50자 이하여야 합니다.") String title) {}
