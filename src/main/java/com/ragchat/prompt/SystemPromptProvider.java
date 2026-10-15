package com.ragchat.prompt;

import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LLM 시스템 프롬프트를 외부 파일에서 로드하여 제공하는 컴포넌트.
 *
 * <p>우선순위: {@code rag.prompt.system-path}로 지정한 외부 파일 → 없거나 실패 시 클래스패스 기본본
 * ({@code classpath:prompts/system-prompt.md}). 외부 파일은 수정 시각이 바뀌면 다음 요청 때 자동으로
 * 다시 읽으므로 <b>재빌드·재배포·재시작 없이</b> 프롬프트를 튜닝할 수 있다.</p>
 */
@Slf4j
@Component
public class SystemPromptProvider {

    // 외부 프롬프트 파일 경로. 비어 있으면 클래스패스 기본본을 사용한다.
    @Value("${rag.prompt.system-path:}")
    private String externalPath;

    // 외부 파일이 없을 때 사용할 클래스패스 기본 프롬프트
    @Value("classpath:prompts/system-prompt.md")
    private Resource classpathDefault;

    private volatile String cached;
    private volatile long cachedModified = -1L;

    @PostConstruct
    void init() {
        this.cached = loadInitial();
    }

    /**
     * 현재 시스템 프롬프트를 반환한다.
     * 외부 파일이 지정돼 있고 수정되었으면 다시 읽어 반영한다.
     *
     * @return 시스템 프롬프트 문자열
     */
    public String get() {
        if (StringUtils.isNotBlank(externalPath)) {
            Path path = Path.of(externalPath);
            try {
                if (Files.exists(path)) {
                    long modified = Files.getLastModifiedTime(path).toMillis();
                    if (modified != cachedModified) {
                        this.cached = Files.readString(path, StandardCharsets.UTF_8);
                        this.cachedModified = modified;
                        log.info("[Prompt] 외부 시스템 프롬프트 변경 반영: {}", externalPath);
                    }
                }
            } catch (IOException e) {
                log.warn("[Prompt] 외부 프롬프트 읽기 실패, 직전 캐시 사용: {}", e.getMessage());
            }
        }
        return cached;
    }

    /**
     * 기동 시 최초 프롬프트를 로드한다. 외부 파일이 유효하면 그것을, 아니면 클래스패스 기본본을 사용한다.
     */
    private String loadInitial() {
        if (StringUtils.isNotBlank(externalPath)) {
            Path path = Path.of(externalPath);
            if (Files.exists(path)) {
                try {
                    this.cachedModified = Files.getLastModifiedTime(path).toMillis();
                    log.info("[Prompt] 외부 시스템 프롬프트 사용: {}", externalPath);
                    return Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("[Prompt] 외부 프롬프트 로드 실패, 클래스패스 기본본 사용: {}", e.getMessage());
                }
            } else {
                log.warn("[Prompt] 외부 프롬프트 경로가 존재하지 않아 클래스패스 기본본 사용: {}", externalPath);
            }
        }
        return loadClasspathDefault();
    }

    private String loadClasspathDefault() {
        try {
            return classpathDefault.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("시스템 프롬프트(클래스패스 기본본) 로드 실패", e);
        }
    }
}
