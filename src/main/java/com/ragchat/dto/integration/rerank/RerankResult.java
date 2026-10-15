package com.ragchat.dto.integration.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Text Embeddings Inference {@code /rerank} 응답 항목. 응답 본문은 이 항목의 배열이다.
 *
 * @param index 요청 {@code texts} 목록에서의 위치
 * @param score 질의와의 관련도 점수 (클수록 관련도가 높다)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RerankResult(
        int index,
        double score
) {
}
