package com.sahmhoot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 컨텍스트가 뜨면 Flyway가 sahmhoot_test에 V1을 적용하고 JPA validate까지 통과한 것이다. */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class SahmhootApplicationTests {

  @Test
  void contextLoads() {}
}
