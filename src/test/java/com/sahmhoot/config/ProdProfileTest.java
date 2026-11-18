package com.sahmhoot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * prod 프로필은 application.yml의 공개된 로컬 기본값(DB 계정, localhost 주소)을 쓰지 않는다(#20).
 *
 * <p>앱을 띄우지 않고 application-prod.yml(우선)과 application.yml 첫 문서만 읽는다. OS 환경변수·시스템 속성은
 * 넣지 않으므로 테스트를 돌리는 셸에 DB_USERNAME 같은 값이 있어도 결과가 같다.
 */
class ProdProfileTest {

  @ParameterizedTest
  @CsvSource({
      "spring.datasource.username, DB_USERNAME",
      "spring.datasource.password, DB_PASSWORD",
      "app.cors.allowed-origins,   CORS_ALLOWED_ORIGINS",
      "app.room.join-base-url,     APP_ROOM_JOIN_BASE_URL"})
  void prodHasNoLocalDefault(String property, String envKey) throws IOException {
    assertThatThrownBy(() -> prodFiles().getProperty(property))
        .hasMessageContaining("Could not resolve placeholder '" + envKey + "'");
  }

  /** 위 테스트가 통과하는 이유가 파일을 못 읽어서가 아님을 확인한다. */
  @Test
  void localDefaultsStillExistWithoutProdFile() throws IOException {
    PropertyResolver base = resolver("application.yml");
    assertThat(base.getProperty("app.cors.allowed-origins")).isEqualTo("http://localhost:5173");
    assertThat(base.getProperty("app.room.join-base-url")).isEqualTo("http://localhost:5173");
    assertThat(base.getProperty("spring.datasource.username")).isNotBlank();
  }

  private static PropertyResolver prodFiles() throws IOException {
    return resolver("application-prod.yml", "application.yml");
  }

  /** 앞에 적은 파일이 우선한다. 각 파일은 프로필 조건이 없는 첫 문서만 쓴다. */
  private static PropertyResolver resolver(String... files) throws IOException {
    var sources = new MutablePropertySources();
    for (String file : files) {
      var loaded = new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).getFirst();
      // YAML 값은 String이 아닌 OriginTracked 값이라 그대로 두면 ${...}를 풀지 않는다. 문자열로 바꿔 둔다.
      Map<String, Object> values = new LinkedHashMap<>();
      for (String name : ((EnumerablePropertySource<?>) loaded).getPropertyNames()) {
        values.put(name, String.valueOf(loaded.getProperty(name)));
      }
      sources.addLast(new MapPropertySource(file, values));
    }
    return new PropertySourcesPropertyResolver(sources);
  }
}
