package com.ragchat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA 감사(Auditing) 기능을 활성화하는 설정.
 *
 * <p>이 설정이 없으면 엔티티의 {@code @CreatedDate}({@code reg_date} 등)가 채워지지 않아
 * 등록 일시가 NULL로 저장된다. {@code @EntityListeners(AuditingEntityListener.class)}가
 * 엔티티에 선언되어 있어도 이 어노테이션이 있어야 실제로 값이 주입된다.</p>
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
