package com.ragchat.dto.integration.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LLM 서버의 {@code /tokenize} API 응답 DTO.
 *
 * <p>입력 문자열의 토큰 수와 토큰화 결과를 전달받을 때 사용한다.</p>
 *
 * @param count 입력 문자열에서 계산된 전체 토큰 수
 * @param maxModelLen 해당 모델이 처리할 수 있는 최대 컨텍스트 토큰 수
 * @param tokens 토큰화된 각 토큰의 정수 ID 목록
 * @param tokenStrs 각 토큰 ID에 대응하는 선택적 문자열 목록
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmTokenizeResponse(
        int count,
        @JsonProperty("max_model_len")
        int maxModelLen,
        List<Integer> tokens,
        @JsonProperty("token_strs")
        List<String> tokenStrs
) {
}
