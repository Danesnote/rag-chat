package com.ragchat.dto.chat.response;

/**
 * 대화방 작업 처리 결과 DTO.
 *
 * @param result 작업 성공 여부
 * @param message 화면에 전달할 처리 결과 메시지
 */
public record ChatActionResponse(boolean result, String message) {

    /**
     * 성공 응답을 생성한다.
     *
     * @param message 성공 메시지
     * @return 성공 상태가 설정된 응답
     */
    public static ChatActionResponse success(String message) {
        return new ChatActionResponse(true, message);
    }

    /**
     * 실패 응답을 생성한다.
     *
     * @param message 실패 사유 메시지
     * @return 실패 상태가 설정된 응답
     */
    public static ChatActionResponse failure(String message) {
        return new ChatActionResponse(false, message);
    }
}
