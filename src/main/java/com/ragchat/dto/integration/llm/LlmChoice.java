package com.ragchat.dto.integration.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * LLM 응답에 포함된 단일 답변 후보 DTO.
 *
 * @param index 답변 후보의 순번
 * @param message 비스트리밍 응답에서 반환되는 완성된 메시지
 * @param delta 스트리밍 응답에서 반환되는 증분 메시지
 * @param logprobs 생성 토큰의 로그 확률 정보
 * @param finishReason 답변 생성이 종료된 이유
 * @param stopReason 모델 또는 서버가 제공하는 세부 중지 사유
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmChoice(
        int index,
        LlmMessage message,
        LlmMessage delta,
        Object logprobs,
        @JsonProperty("finish_reason")
        String finishReason,
        @JsonProperty("stop_reason")
        Object stopReason
) {
}
