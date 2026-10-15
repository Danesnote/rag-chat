package com.ragchat.dto.integration.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * LLM 요청 처리에 사용된 토큰 수 통계 DTO.
 *
 * @param promptTokens 요청 프롬프트에 사용된 토큰 수
 * @param completionTokens 생성된 답변에 사용된 토큰 수
 * @param totalTokens 프롬프트와 답변을 합한 전체 토큰 수
 * @param promptTokensDetails 프롬프트 토큰의 캐시 사용 등 세부 정보
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmUsage(
        @JsonProperty("prompt_tokens")
        int promptTokens,
        @JsonProperty("completion_tokens")
        int completionTokens,
        @JsonProperty("total_tokens")
        int totalTokens,
        @JsonProperty("prompt_tokens_details")
        LlmPromptTokensDetails promptTokensDetails
) {
}
