package com.ragchat.dto.integration.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 프롬프트 토큰 사용량의 세부 통계 DTO.
 *
 * @param cachedTokens 프롬프트 토큰 중 캐시에서 재사용된 토큰 수
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmPromptTokensDetails(
        @JsonProperty("cached_tokens")
        Integer cachedTokens
) {
}
