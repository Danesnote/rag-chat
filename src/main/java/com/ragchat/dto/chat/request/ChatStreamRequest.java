package com.ragchat.dto.chat.request;

/**
 * 질문을 전송하고 LLM 답변 스트리밍을 시작하는 요청 DTO.
 *
 * @param question 사용자가 입력한 질문
 * @param chatSeq 기존 대화방 식별자. 새 대화이면 {@code null}
 * @param model 화면에서 선택한 모델 식별값
 * @param retrievalMode 검색 방식(1: 하이브리드, 2: 하이브리드 + 인접 청크 확장). {@code null}이면 서버 기본값 사용
 */
public record ChatStreamRequest(
        String question,
        Long chatSeq,
        String model,
        Integer retrievalMode
) {
}
