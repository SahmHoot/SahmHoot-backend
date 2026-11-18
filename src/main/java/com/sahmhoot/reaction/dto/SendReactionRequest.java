package com.sahmhoot.reaction.dto;

/** /app/rooms/{roomId}/reaction 전송 본문. 허용 이모지 검사는 ReactionService가 한다. */
public record SendReactionRequest(String emoji) {}
