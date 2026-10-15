package com.ragchat.dto.integration.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OpenAI 호환 {@code /v1/embeddings} 응답 DTO.
 *
 * @param data 입력 문자열별 임베딩. 서버에 따라 순서가 보장되지 않으므로 {@code index} 로 정렬해 쓴다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbeddingResponse(
        List<Item> data
) {

    /**
     * @param index 요청 {@code input} 목록에서의 위치
     * @param embedding 임베딩 벡터
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            int index,
            float[] embedding
    ) {
    }
}
