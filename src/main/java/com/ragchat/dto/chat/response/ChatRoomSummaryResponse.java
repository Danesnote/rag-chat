package com.ragchat.dto.chat.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ragchat.entity.ChatListEntity;

import java.time.LocalDateTime;

/**
 * 왼쪽 메뉴에 표시하는 대화방 요약 응답 DTO.
 *
 * @param id 대화방 식별자
 * @param text 화면에 표시할 대화방 제목
 * @param modDate 마지막 대화(활동) 일시. 목록 정렬 기준
 */
public record ChatRoomSummaryResponse(
        Long id,
        String text,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm", timezone = "Asia/Seoul")
        LocalDateTime modDate
) {

    /**
     * 대화방 엔티티를 요약 응답으로 변환한다.
     *
     * @param entity 변환할 대화방 엔티티
     * @return 식별자, 제목, 마지막 활동 일시를 포함한 요약 응답
     */
    public static ChatRoomSummaryResponse from(ChatListEntity entity) {
        return new ChatRoomSummaryResponse(entity.getId(), entity.getTitle(), entity.getModDate());
    }
}
