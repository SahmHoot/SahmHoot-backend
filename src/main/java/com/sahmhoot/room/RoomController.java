package com.sahmhoot.room;

import com.sahmhoot.auth.AuthUser;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {
  private final RoomService service;
  private final RoomManagementService management;

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
