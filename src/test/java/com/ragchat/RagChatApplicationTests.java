package com.ragchat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 스프링 컨텍스트 기동 테스트.
 *
 * <p>JPA 가 {@code ddl-auto=validate} 로 실제 DB 스키마를 검증하므로 DB 가 있어야 통과한다.
 * {@code DB_URL} 환경변수가 설정된 경우에만 실행한다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class RagChatApplicationTests {

    @Test
    void contextLoads() {
    }

}
