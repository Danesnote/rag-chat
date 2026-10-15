package com.ragchat.dto.chat.request;

/**
 * 대화방 목록을 Slice 방식으로 조회하는 요청 DTO.
 *
 * @param page 조회할 페이지 번호(0부터 시작)
 * @param size 페이지당 조회할 대화방 수
 */
public record ChatRoomSliceRequest(Integer page, Integer size) {

    private static final int DEFAULT_SIZE = 10;     // 요청 크기가 없을 때 사용하는 기본 페이지 크기
    private static final int MAX_SIZE = 50;         // 한 번의 요청에서 허용하는 최대 페이지 크기

    /**
     * 유효한 페이지 번호를 반환한다.
     *
     * @return 페이지 번호가 없거나 음수이면 0, 그 외에는 요청값
     */
    public int pageNumber() {
        return page == null || page < 0 ? 0 : page;
    }

    /**
     * 허용 범위로 보정한 페이지 크기를 반환한다.
     *
     * @return 1 이상 50 이하의 페이지 크기
     */
    public int pageSize() {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        return Math.clamp(size, 1, MAX_SIZE);
    }
}
