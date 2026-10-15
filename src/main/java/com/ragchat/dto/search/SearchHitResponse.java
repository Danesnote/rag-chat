package com.ragchat.dto.search;

import com.ragchat.service.RetrievalService;

/**
 * 검색 결과 한 건.
 *
 * @param chunkId 청크 ID ({@code {문서ID}_{청크번호}})
 * @param title 문서명
 * @param version 문서 버전(개정차수)
 * @param effectiveDate 시행일자
 * @param origin 검색 결과(HIT)인지 인접 청크(PREV/NEXT)인지
 * @param score 검색 점수 (리랭커 사용 시 리랭커 점수, 인접 청크는 0)
 * @param content 청크 본문
 */
public record SearchHitResponse(
        String chunkId,
        String title,
        String version,
        String effectiveDate,
        String origin,
        double score,
        String content
) {

    public static SearchHitResponse from(RetrievalService.RetrievalItem item) {
        return new SearchHitResponse(
                item.chunk().chunkId(),
                item.chunk().title(),
                item.chunk().version(),
                item.chunk().effectiveDate(),
                item.origin().name(),
                item.chunk().score(),
                item.chunk().content()
        );
    }
}
