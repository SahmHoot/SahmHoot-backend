package com.sahmhoot.room;

import com.sahmhoot.auth.exception.ApiException;
import com.sahmhoot.auth.exception.ErrorCode;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class RoomService {
  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;
  private final UserRepository users;
  private final JoinCodeGenerator codes;
  private final TransactionTemplate creation;
  private final String joinBaseUrl;

  public RoomService(RoomRepository rooms, RoomParticipantRepository participants,
      UserRepository users, JoinCodeGenerator codes, PlatformTransactionManager transactions,
      @Value("${app.room.join-base-url:http://localhost:5173}") String joinBaseUrl) {
    this.rooms = rooms;
    this.participants = participants;
    this.users = users;
    this.codes = codes;
    this.joinBaseUrl = joinBaseUrl.replaceAll("/+$", "");
    this.creation = new TransactionTemplate(transactions);
    // A failed UNIQUE insert rolls back before the next attempt starts a fresh transaction.
    this.creation.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public CreateRoomResponse create(Long userId, CreateRoomRequest request) {
    var host = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    if (host.getRole() != Role.PROFESSOR) throw new ApiException(ErrorCode.FORBIDDEN);
    for (int attempt = 0; attempt < 10; attempt++) {
      String code = codes.generate();
      try {
        return creation.execute(tx -> {
          Room room = rooms.saveAndFlush(new Room(host.getId(), request.title(), code));
          participants.saveAndFlush(new RoomParticipant(room.getId(), host.getId(), host.getName()));
          String joinUrl = UriComponentsBuilder.fromUriString(joinBaseUrl)
              .pathSegment("join", code).build().toUriString();
          return new CreateRoomResponse(room.getId(), room.getTitle(), room.getStatus(), code,
              joinUrl, room.getCreatedAt());
        });
      } catch (DataIntegrityViolationException failure) {
        // DB uniqueness includes CLOSED rooms and concurrent requests; other failures must surface.
        if (!rooms.existsByCode(code)) throw failure;
      }
    }
    throw new IllegalStateException("사용 가능한 입장 코드를 생성하지 못했습니다.");
  }
}
