package com.ragchat.dto.document;

/**
 * 문서 적재 결과.
 *
 * @param documentId 발급된 문서 ID. 청크 ID 는 {@code {documentId}_{청크번호}} 형식이다.
 * @param title 문서명
 * @param chunkCount 생성된 청크 수
 */
public record DocumentCreateResponse(
        long documentId,
        String title,
        int chunkCount
) {
}
