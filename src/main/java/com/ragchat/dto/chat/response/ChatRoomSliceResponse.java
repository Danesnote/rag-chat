package com.ragchat.dto.chat.response;

import com.ragchat.entity.ChatListEntity;
import org.springframework.data.domain.Slice;

import java.util.List;

/**
 * 대화방 목록의 Slice 조회 결과 DTO.
 *
 * @param content 현재 페이지의 대화방 요약 목록
 * @param hasNext 다음 페이지 존재 여부
 * @param page 현재 페이지 번호(0부터 시작)
 */
public record ChatRoomSliceResponse(
        List<ChatRoomSummaryResponse> content,
        boolean hasNext,
        int page
) {

    /**
     * 대화방 엔티티 Slice를 화면 응답으로 변환한다.
     *
     * @param slice 조회된 대화방 Slice
     * @return 요약 목록과 다음 페이지 정보를 포함한 응답
     */
    public static ChatRoomSliceResponse from(Slice<ChatListEntity> slice) {
        List<ChatRoomSummaryResponse> content = slice.getContent()
                .stream()
                .map(ChatRoomSummaryResponse::from)
                .toList();

        return new ChatRoomSliceResponse(content, slice.hasNext(), slice.getNumber());
    }
}
