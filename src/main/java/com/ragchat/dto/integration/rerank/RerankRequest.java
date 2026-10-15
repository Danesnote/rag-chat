package com.ragchat.dto.integration.rerank;

import java.util.List;

/**
 * Text Embeddings Inference {@code /rerank} 요청 DTO.
 *
 * @param query 문서와의 관련도를 평가할 질의
 * @param texts 재정렬할 문서 본문 목록. 응답의 {@code index} 가 이 목록의 순서와 대응한다.
 * @param truncate 모델 최대 길이를 넘는 입력을 서버에서 자를지 여부
 */
public record RerankRequest(
        String query,
        List<String> texts,
        boolean truncate
) {
}
