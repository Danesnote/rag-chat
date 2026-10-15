package com.ragchat.dto.integration.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * vLLM Chat Completions 스트리밍 옵션.
 *
 * @param includeUsage true이면 스트리밍 마지막 청크에 토큰 사용량(usage)을 포함시킨다.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmStreamOptions(
        @JsonProperty("include_usage")
        Boolean includeUsage
) {
}
