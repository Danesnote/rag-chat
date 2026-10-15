package com.ragchat.retrieval;

/**
 * 검색 단위인 문서 청크 한 건.
 *
 * <p>화면 출처 표시와 LLM [CONTEXT] 구성에 같은 값을 사용한다.
 * 문서를 가리키는 식별자는 {@link #chunkId()} 하나뿐이며, 조문번호(제N조)는
 * 메타데이터가 아니라 본문 텍스트 안에만 있다.</p>
 *
 * @param documentId 원본 문서 식별자
 * @param chunkNo 문서 안에서의 청크 순번(0부터). 앞/뒤 청크는 이 값 ±1 이다.
 * @param title 문서명. 출처 제목으로 쓴다.
 * @param version 문서 버전(개정차수). <b>조문번호가 아니다.</b>
 * @param effectiveDate 시행일자
 * @param content 청크 본문
 * @param score 검색 단계에서 매겨진 점수 (벡터 유사도 / 키워드 점수 / RRF / 리랭커 점수 중 마지막 단계의 값)
 */
public record DocumentChunk(
        long documentId,
        int chunkNo,
        String title,
        String version,
        String effectiveDate,
        String content,
        double score
) {

    /**
     * 청크 식별자. {@code {문서번호}_{청크번호(4자리)}} 형식이며 화면의 문서번호로 노출된다.
     * 예) {@code 12_0003}
     */
    public String chunkId() {
        return documentId + "_" + String.format("%04d", chunkNo);
    }

    public DocumentChunk withScore(double newScore) {
        return new DocumentChunk(documentId, chunkNo, title, version, effectiveDate, content, newScore);
    }

    public boolean hasContent() {
        return content != null && !content.isBlank();
    }
}
