package com.ragchat.dto.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragchat.dto.chat.request.ChatActionRequest;
import com.ragchat.dto.chat.request.ChatRoomSliceRequest;
import com.ragchat.dto.chat.response.ChatActionResponse;
import com.ragchat.dto.chat.response.ChatRoomSliceResponse;
import com.ragchat.type.ChatAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatDtoContractTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void bindsLowercaseChatActionFromFrontend() throws Exception {
        ChatActionRequest request = objectMapper.readValue(
                "{\"action\":\"update\",\"id\":1,\"title\":\"새 이름\"}",
                ChatActionRequest.class
        );

        assertThat(request.action()).isEqualTo(ChatAction.UPDATE);
        assertThat(request.id()).isEqualTo(1L);
        assertThat(request.title()).isEqualTo("새 이름");
    }

    @Test
    void preservesFrontendActionResponseFields() throws Exception {
        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(ChatActionResponse.success("정상 처리되었습니다."))
        );

        assertThat(json.get("result").asBoolean()).isTrue();
        assertThat(json.get("message").asText()).isEqualTo("정상 처리되었습니다.");
    }

    @Test
    void appliesSafeChatRoomSliceDefaultsAndLimits() throws Exception {
        ChatRoomSliceRequest defaults = objectMapper.readValue("{}", ChatRoomSliceRequest.class);
        ChatRoomSliceRequest bounded = new ChatRoomSliceRequest(-1, 100);

        assertThat(defaults.pageNumber()).isZero();
        assertThat(defaults.pageSize()).isEqualTo(10);
        assertThat(bounded.pageNumber()).isZero();
        assertThat(bounded.pageSize()).isEqualTo(50);
    }

    @Test
    void preservesFrontendChatRoomSliceResponseFields() throws Exception {
        ChatRoomSliceResponse response = new ChatRoomSliceResponse(List.of(), true, 2);
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.get("content").isArray()).isTrue();
        assertThat(json.get("hasNext").asBoolean()).isTrue();
        assertThat(json.get("page").asInt()).isEqualTo(2);
    }
}
