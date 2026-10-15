package com.ragchat.dto.integration.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * LLM 서버의 {@code /tokenize} API에 전달하는 요청 DTO.
 *
 * <p>입력 프롬프트를 모델의 토크나이저로 변환하고 토큰 수를 확인할 때 사용한다.</p>
 *
 * @param model 토큰 계산에 사용할 모델 식별자
 * @param prompt 토큰으로 변환할 입력 문자열
 * @param addSpecialTokens BOS 등의 특수 토큰을 결과에 포함할지 여부
 * @param returnTokenStrs 토큰 ID와 함께 토큰 문자열을 반환받을지 여부
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmTokenizeRequest(
        String model,
        String prompt,
        @JsonProperty("add_special_tokens")
        Boolean addSpecialTokens,
        @JsonProperty("return_token_strs")
        Boolean returnTokenStrs
) {
}
