package com.ragchat.dto.chat.request;

import com.ragchat.type.ChatAction;

/**
 * 대화방에 대한 아카이브, 이름 변경, 삭제 작업 요청 DTO.
 *
 * @param action 수행할 대화방 작업 유형
 * @param id 작업 대상 대화방 식별자
 * @param title 이름 변경 시 적용할 새 대화방 제목
 */
public record ChatActionRequest(
        ChatAction action,
        Long id,
        String title
) {
}
