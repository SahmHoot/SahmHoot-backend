package com.sahmhoot.websocket.presence;

/**
 * 세션 추적(#65)이 구현하고 Room의 접속 인원 집계·소유자 연결 확인(#45, #46)이 사용하는 계약.
 * 추적 대상은 권한 검사에 성공한 방 채널 구독이며 CONNECT만으로 접속자를 늘리지 않는다.
 */
public interface RoomPresencePort {

  /**
   * 방의 사용자 집합과 소유자 연결 유무를 같은 시점의 불변 스냅샷으로 반환한다.
   * 집합은 users.id이며 접속 중인 소유자도 포함한다. 여러 탭은 한 사용자로 합친다.
   * 제공자는 ownerOnline을 onlineUserIds.contains(해당 방의 hostId)와 일치시켜야 한다.
   * 구독 세션이 없으면 빈 집합과 ownerOnline=false를 반환하고, null은 반환하지 않는다.
   * 방의 존재·상태·참가 권한 검증은 소비자가 담당한다.
   */
  RoomPresenceSnapshot getPresence(Long roomId);
}
