package com.ragchat.dto.integration.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 요청과 응답에서 사용하는 단일 대화 메시지 DTO.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmMessage {

    private String role;                    // 메시지 작성 역할({@code system}, {@code user}, {@code assistant} 등)
    private String content;                 // 사용자에게 노출되는 메시지 본문.

    @JsonProperty("reasoning_content")
    private String reasoningContent;        // 추론 지원 모델이 반환하는 내부 추론 내용.

    @JsonProperty("tool_calls")
    private List<LlmToolCall> toolCalls;    // 모델이 요청한 도구 호출 목록.

    /**
     * 역할과 본문만으로 기본 메시지를 생성한다.
     *
     * @param role 메시지 작성 역할
     * @param content 메시지 본문
     */
    public LlmMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
