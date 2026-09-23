package com.sahmhoot.common.health;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 서버가 떠 있는지 확인하는 용도. 인증 없이 호출할 수 있다. */
@RestController
public class HealthController {

  @GetMapping("/api/health")
  public Map<String, String> health() {
    return Map.of("status", "UP");
  }
}
