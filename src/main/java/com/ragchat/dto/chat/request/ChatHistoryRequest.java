package com.ragchat.dto.chat.request;

/**
 * 특정 대화방의 메시지 이력을 조회하는 요청 DTO.
 *
 * @param chatSeq 조회할 대화방 식별자
 */
public record ChatHistoryRequest(Long chatSeq) {
}
