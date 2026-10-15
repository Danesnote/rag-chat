package com.ragchat.dto.chat.response;

import com.ragchat.entity.ChatMessageEntity;

/**
 * 화면에 제공하는 단일 대화 메시지 응답 DTO.
 *
 * @param id 메시지 식별자
 * @param role 메시지 작성 역할({@code USER}, {@code ASSISTANT} 등)
 * @param content 메시지 본문
 * @param searchContent 답변 생성에 사용한 검색 결과 정보
 */
public record ChatMessageResponse(
        Long id,
        String role,
        String content,
        String searchContent
) {

    /**
     * 메시지 엔티티를 화면 응답 DTO로 변환한다.
     *
     * @param entity 변환할 메시지 엔티티
     * @return 엔티티 값으로 구성된 메시지 응답
     */
    public static ChatMessageResponse from(ChatMessageEntity entity) {
        return new ChatMessageResponse(
                entity.getId(),
                entity.getRole().toString(),
                entity.getContent(),
                entity.getSearchContent()
        );
    }
}
