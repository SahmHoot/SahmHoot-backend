package com.sahmhoot.room.repository;

import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {
  boolean existsByCode(String code);

  Optional<Room> findByCode(String code);

  List<Room> findByHostIdAndStatusNotOrderByCreatedAtDescIdDesc(Long hostId, RoomStatus status);
}
