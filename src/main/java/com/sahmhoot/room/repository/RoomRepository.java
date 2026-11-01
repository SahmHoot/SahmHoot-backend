package com.sahmhoot.room.repository;

import com.sahmhoot.room.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {
  boolean existsByCode(String code);
}
