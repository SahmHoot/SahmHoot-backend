package com.sahmhoot.room;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {
  boolean existsByCode(String code);

  List<Room> findByHostIdAndStatusNotOrderByCreatedAtDescIdDesc(Long hostId, RoomStatus status);
}
