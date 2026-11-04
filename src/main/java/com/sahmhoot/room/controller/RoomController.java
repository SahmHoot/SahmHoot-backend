package com.sahmhoot.room.controller;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.room.dto.CreateRoomRequest;
import com.sahmhoot.room.dto.CreateRoomResponse;
import com.sahmhoot.room.dto.JoinRoomRequest;
import com.sahmhoot.room.dto.JoinRoomResponse;
import com.sahmhoot.room.dto.RoomByCodeResponse;
import com.sahmhoot.room.dto.RoomSummaryResponse;
import com.sahmhoot.room.service.RoomEntryService;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.room.service.RoomService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {
  private final RoomService service;
  private final RoomManagementService management;
  private final RoomEntryService entry;

  @GetMapping("/by-code/{joinCode}")
  public RoomByCodeResponse byCode(@AuthenticationPrincipal AuthUser me,
      @PathVariable String joinCode) {
    return entry.byCode(me.userId(), joinCode);
  }

  @PostMapping("/by-code/{joinCode}/participants")
  public ResponseEntity<JoinRoomResponse> join(@AuthenticationPrincipal AuthUser me,
      @PathVariable String joinCode, @Valid @RequestBody JoinRoomRequest request) {
    var result = entry.join(me.userId(), joinCode, request);
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.response());
  }

  @GetMapping("/mine")
  public List<RoomSummaryResponse> mine(@AuthenticationPrincipal AuthUser me) {
    return management.mine(me.userId());
  }

  @PatchMapping("/{roomId}/close")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void close(@AuthenticationPrincipal AuthUser me, @PathVariable Long roomId) {
    management.close(roomId, me.userId());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CreateRoomResponse create(@AuthenticationPrincipal AuthUser me,
      @Valid @RequestBody CreateRoomRequest request) {
    return service.create(me.userId(), request);
  }
}
