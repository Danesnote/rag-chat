package com.ragchat.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 컨텍스트(프롬프트) 토큰 예산 값 모음.
 *
 * <p>application.properties의 토큰 예산 설정을 한 곳에 바인딩하여 컨텍스트 관리(윈도잉/요약)에서 사용한다.
 * 프로파일별 누락에 대비해 모든 값에 기본값을 둔다.</p>
 */
@Getter
@Component
public class ContextBudget {

    @Value("${server.context.max-tokens:131072}")
    private int maxContextTokens;
    @Value("${server.context.reserve-tokens:4096}")
    private int reserveTokens;
    @Value("${request.generation.medium.max-tokens:16384}")
    private int generationMaxTokens;
    @Value("${input.safety-margin-tokens:2000}")
    private int safetyMarginTokens;

    @Value("${history.retained.target-tokens:8000}")
    private int retainedTargetTokens;
    @Value("${history.summarize.trigger-tokens:16000}")
    private int summarizeTriggerTokens;
    @Value("${history.memory.max-tokens:12000}")
    private int memoryMaxTokens;

    @Value("${summary.target-tokens:2500}")
    private int summaryTargetTokens;
    @Value("${summary.max-tokens:4000}")
    private int summaryMaxTokens;
    @Value("${summary.compression.target-tokens:2000}")
    private int summaryCompressionTargetTokens;

    /**
     * 입력(프롬프트)에 사용할 수 있는 총 토큰 상한.
     * = 컨텍스트 한도 - 예약 - 답변 생성 예산 - 안전 여유
     */
    public int inputBudget() {
        return maxContextTokens - reserveTokens - generationMaxTokens - safetyMarginTokens;
    }
}
