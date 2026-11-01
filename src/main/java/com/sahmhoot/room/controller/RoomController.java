package com.sahmhoot.room.controller;

import com.sahmhoot.auth.dto.AuthUser;
import com.sahmhoot.room.dto.CreateRoomRequest;
import com.sahmhoot.room.dto.CreateRoomResponse;
import com.sahmhoot.room.service.RoomService;
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
