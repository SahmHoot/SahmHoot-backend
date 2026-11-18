package com.sahmhoot.chat.repository;

import com.sahmhoot.chat.entity.ChatRestriction;
import com.sahmhoot.chat.entity.ChatRestrictionId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRestrictionRepository extends JpaRepository<ChatRestriction, ChatRestrictionId> {}
