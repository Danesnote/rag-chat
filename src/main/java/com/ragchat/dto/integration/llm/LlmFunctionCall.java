package com.ragchat.dto.integration.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * LLM이 요청한 함수 호출의 이름과 인자를 표현하는 DTO.
 *
 * @param name 호출할 함수 이름
 * @param arguments 함수에 전달할 JSON 형식의 인자 문자열
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmFunctionCall(String name, String arguments) {
}
