package com.ragchat.dto.integration.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmTokenizePayloadTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesTokenizeRequest() throws Exception {
        LlmTokenizeRequest request = LlmTokenizeRequest.builder()
                .model("openai/gpt-oss-120b")
                .prompt("안녕하세요")
                .addSpecialTokens(true)
                .returnTokenStrs(true)
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertThat(json.get("model").asText()).isEqualTo("openai/gpt-oss-120b");
        assertThat(json.get("prompt").asText()).isEqualTo("안녕하세요");
        assertThat(json.get("add_special_tokens").asBoolean()).isTrue();
        assertThat(json.get("return_token_strs").asBoolean()).isTrue();
    }

    @Test
    void omitsUnsetOptionalRequestFields() throws Exception {
        LlmTokenizeRequest request = LlmTokenizeRequest.builder()
                .model("openai/gpt-oss-120b")
                .prompt("안녕하세요")
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertThat(json.has("add_special_tokens")).isFalse();
        assertThat(json.has("return_token_strs")).isFalse();
    }

    @Test
    void deserializesTokenizeResponse() throws Exception {
        String json = """
                {
                  "count": 3,
                  "max_model_len": 131072,
                  "tokens": [123, 456, 789],
                  "token_strs": ["안", "녕", "하세요"]
                }
                """;

        LlmTokenizeResponse response = objectMapper.readValue(json, LlmTokenizeResponse.class);

        assertThat(response.count()).isEqualTo(3);
        assertThat(response.maxModelLen()).isEqualTo(131072);
        assertThat(response.tokens()).containsExactly(123, 456, 789);
        assertThat(response.tokenStrs()).containsExactly("안", "녕", "하세요");
    }
}
