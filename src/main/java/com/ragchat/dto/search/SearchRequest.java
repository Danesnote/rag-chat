package com.ragchat.dto.search;

/**
 * 검색 단독 실행 요청 (LLM 호출 없이 검색 품질을 확인하는 용도).
 *
 * @param query 검색 질의
 * @param mode 검색 방식 (1: 하이브리드, 2: 하이브리드 + 인접 청크 확장). {@code null} 이면 서버 기본값
 */
public record SearchRequest(
        String query,
        Integer mode
) {
}
