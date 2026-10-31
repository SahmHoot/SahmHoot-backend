package com.sahmhoot.room;

import com.sahmhoot.auth.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {
  private final RoomService service;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CreateRoomResponse create(@AuthenticationPrincipal AuthUser me,
      @Valid @RequestBody CreateRoomRequest request) {
    return service.create(me.userId(), request);
  }
}
