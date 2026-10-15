package com.ragchat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 비동기 실행(@Async) 활성화 설정.
 *
 * <p>이 설정이 있어야 {@code @Async} 메서드가 실제로 별도 스레드에서 실행된다.
 * ({@code spring.threads.virtual.enabled=true}이므로 가상 스레드로 처리되어 오버헤드가 작다.)
 * 메시지 저장과 토큰 수 계산이 답변 응답 경로를 막지 않도록 하는 데 사용된다.</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
