package com.sahmhoot.room.repository;

import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {
  boolean existsByCode(String code);

  List<Room> findByHostIdAndStatusNotOrderByCreatedAtDescIdDesc(Long hostId, RoomStatus status);
}
