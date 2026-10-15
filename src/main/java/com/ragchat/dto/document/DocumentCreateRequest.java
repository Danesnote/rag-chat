package com.ragchat.dto.document;

/**
 * 문서 적재 요청.
 *
 * @param title 문서명 (필수). 출처 제목으로 표시된다.
 * @param version 문서 버전(개정차수). 없으면 {@code null}
 * @param effectiveDate 시행일자 (예: 2025-01-01). 없으면 {@code null}
 * @param content 문서 본문 (필수). 조문형({@code 제N조}) · Markdown · 일반 텍스트를 지원한다.
 */
public record DocumentCreateRequest(
        String title,
        String version,
        String effectiveDate,
        String content
) {
}
