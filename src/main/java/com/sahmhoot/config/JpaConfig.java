package com.sahmhoot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** {@code @CreatedDate}·{@code @LastModifiedDate} 자동 기록. 메인 클래스에 두면 슬라이스 테스트가 깨져서 따로 둔다. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {}
