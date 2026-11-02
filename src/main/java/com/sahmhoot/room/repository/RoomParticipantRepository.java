package com.sahmhoot.room.repository;

import com.sahmhoot.room.entity.RoomParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomParticipantRepository extends JpaRepository<RoomParticipant, Long> {}
