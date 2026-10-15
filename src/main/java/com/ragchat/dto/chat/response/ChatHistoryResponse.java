package com.ragchat.dto.chat.response;

import java.util.List;

/**
 * 특정 대화방의 메시지 이력 조회 결과 DTO.
 *
 * @param result 조회 성공 여부
 * @param data 시간순으로 반환할 대화 메시지 목록
 */
public record ChatHistoryResponse(boolean result, List<ChatMessageResponse> data) {

    /**
     * 메시지 목록을 포함하는 성공 응답을 생성한다.
     *
     * @param data 조회된 메시지 목록
     * @return 성공 상태의 대화 이력 응답
     */
    public static ChatHistoryResponse success(List<ChatMessageResponse> data) {
        return new ChatHistoryResponse(true, data);
    }

    /**
     * 빈 목록을 포함하는 실패 응답을 생성한다.
     *
     * @return 실패 상태의 대화 이력 응답
     */
    public static ChatHistoryResponse failure() {
        return new ChatHistoryResponse(false, List.of());
    }
}
