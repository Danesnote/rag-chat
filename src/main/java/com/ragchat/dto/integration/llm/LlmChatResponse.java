package com.ragchat.dto.integration.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LLM 서버의 Chat Completions API 응답 DTO.
 *
 * <p>일반 응답과 스트리밍 청크를 모두 역직렬화할 수 있도록 구성한다.</p>
 *
 * @param id LLM 서버가 생성한 응답 식별자
 * @param object 응답 객체 유형
 * @param created 응답 생성 시각의 Unix 타임스탬프
 * @param model 응답을 생성한 모델 식별자
 * @param choices 모델이 생성한 답변 후보 목록
 * @param usage 요청과 응답에서 사용된 토큰 통계
 * @param promptLogprobs 모델이 제공하는 프롬프트 로그 확률 정보
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmChatResponse(
        String id,
        String object,
        long created,
        String model,
        List<LlmChoice> choices,
        LlmUsage usage,
        @JsonProperty("prompt_logprobs")
        Object promptLogprobs
) {
}
