package com.ragchat.dto.integration.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * LLM이 생성한 단일 도구 호출 정보 DTO.
 *
 * @param id 도구 호출을 구분하는 식별자
 * @param type 호출할 도구 유형
 * @param function 함수 호출 이름과 인자 정보
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmToolCall(String id, String type, LlmFunctionCall function) {
}
