package com.ragchat.dto.integration.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * vLLM 서버의 Chat Completions API(OpenAI 호환)에 전달하는 요청 DTO.
 *
 * <p>{@code @JsonInclude(NON_NULL)}이므로 값을 설정하지 않은(null) 선택 필드는 직렬화에서 제외된다.
 * 따라서 표준 OpenAI 파라미터만 채우면 그대로 표준 요청이 되고, {@code reasoningEffort} 등을 채우면
 * 추론형 모델(gpt-oss)용 요청이 된다.</p>
 *
 * @param model 답변 생성에 사용할 모델 식별자
 * @param messages 시스템 지시문과 대화 문맥
 * @param temperature 답변 생성의 무작위성을 조절하는 값
 * @param topP 누적 확률 기반 샘플링(nucleus sampling) 임계값
 * @param n 생성할 답변 후보 수
 * @param maxTokens 생성할 답변의 최대 토큰 수
 * @param stop 생성을 중단시킬 문자열 목록
 * @param stream 응답을 스트리밍 방식으로 받을지 여부
 * @param presencePenalty 이미 등장한 토큰의 재등장을 억제하는 정도
 * @param frequencyPenalty 자주 등장한 토큰의 반복을 억제하는 정도
 * @param user 요청을 발생시킨 최종 사용자 식별자
 * @param reasoningEffort 추론 지원 모델(gpt-oss 등)에서 사용할 추론 강도. 표준 모델 요청 시 생략한다.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmChatRequest(
        String model,
        List<LlmMessage> messages,
        double temperature,
        @JsonProperty("top_p")
        Double topP,
        Integer n,
        @JsonProperty("max_tokens")
        int maxTokens,
        List<String> stop,
        boolean stream,
        @JsonProperty("stream_options")
        LlmStreamOptions streamOptions,
        @JsonProperty("presence_penalty")
        Double presencePenalty,
        @JsonProperty("frequency_penalty")
        Double frequencyPenalty,
        String user,
        @JsonProperty("reasoning_effort")
        String reasoningEffort,
        Integer seed
) {
}
