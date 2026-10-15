package com.ragchat.integration.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragchat.dto.integration.llm.LlmChatRequest;
import com.ragchat.dto.integration.llm.LlmChatResponse;
import com.ragchat.dto.integration.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmChatPayloadTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesGptOssVllmRequest() throws Exception {
        LlmChatRequest request = LlmChatRequest.builder()
                .model("openai/gpt-oss-120b")
                .messages(List.of(
                        new LlmMessage("system", "You are a helpful assistant."),
                        new LlmMessage("user", "안녕하세요")
                ))
                .maxTokens(1024)
                .temperature(0.7)
                .reasoningEffort("medium")
                .stream(false)
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertThat(json.get("model").asText()).isEqualTo("openai/gpt-oss-120b");
        assertThat(json.get("messages").size()).isEqualTo(2);
        assertThat(json.get("max_tokens").asInt()).isEqualTo(1024);
        assertThat(json.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(json.get("reasoning_effort").asText()).isEqualTo("medium");
        assertThat(json.get("stream").asBoolean()).isFalse();
        assertThat(json.has("prompt")).isFalse();
    }

    @Test
    void serializesStandardVllmRequest() throws Exception {
        LlmChatRequest request = LlmChatRequest.builder()
                .model("meta-llama/Meta-Llama-3-8B-Instruct")
                .messages(List.of(
                        new LlmMessage("system", "You are a helpful assistant."),
                        new LlmMessage("user", "Hello!")
                ))
                .temperature(0.7)
                .topP(0.9)
                .n(1)
                .maxTokens(512)
                .stop(List.of("\n\n", "User:"))
                .stream(true)
                .presencePenalty(0.0)
                .frequencyPenalty(0.0)
                .user("user-123")
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertThat(json.get("model").asText()).isEqualTo("meta-llama/Meta-Llama-3-8B-Instruct");
        assertThat(json.get("top_p").asDouble()).isEqualTo(0.9);
        assertThat(json.get("n").asInt()).isEqualTo(1);
        assertThat(json.get("max_tokens").asInt()).isEqualTo(512);
        assertThat(json.get("stop").size()).isEqualTo(2);
        assertThat(json.get("stop").get(0).asText()).isEqualTo("\n\n");
        assertThat(json.get("stream").asBoolean()).isTrue();
        assertThat(json.get("presence_penalty").asDouble()).isEqualTo(0.0);
        assertThat(json.get("frequency_penalty").asDouble()).isEqualTo(0.0);
        assertThat(json.get("user").asText()).isEqualTo("user-123");
        // 표준 요청에는 추론 강도를 넣지 않으므로 직렬화에서 제외되어야 한다.
        assertThat(json.has("reasoning_effort")).isFalse();
    }

    @Test
    void deserializesStandardVllmStreamChunk() throws Exception {
        String json = """
                {
                  "id": "chatcmpl-9f8a31b4-2e11-4a12-83fc-391a9b1c7820",
                  "object": "chat.completion.chunk",
                  "created": 1723456789,
                  "model": "meta-llama/Meta-Llama-3-8B-Instruct",
                  "choices": [
                    {
                      "index": 0,
                      "delta": {
                        "role": "assistant",
                        "content": "Hello"
                      },
                      "logprobs": null,
                      "finish_reason": null
                    }
                  ]
                }
                """;

        LlmChatResponse response = objectMapper.readValue(json, LlmChatResponse.class);

        assertThat(response.object()).isEqualTo("chat.completion.chunk");
        assertThat(response.created()).isEqualTo(1723456789L);
        assertThat(response.choices().getFirst().delta().getRole()).isEqualTo("assistant");
        assertThat(response.choices().getFirst().delta().getContent()).isEqualTo("Hello");
        assertThat(response.choices().getFirst().finishReason()).isNull();
        // 스트리밍 청크에는 usage가 없으므로 null 이어야 한다.
        assertThat(response.usage()).isNull();
    }

    @Test
    void deserializesGptOssVllmResponse() throws Exception {
        String json = """
                {
                  "id": "chatcmpl-8f3a2b1c9d4e5f6a",
                  "object": "chat.completion",
                  "created": 1753000000,
                  "model": "openai/gpt-oss-120b",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "reasoning_content": "한국어로 답변하자.",
                      "content": "안녕하세요! 무엇을 도와드릴까요?",
                      "tool_calls": null
                    },
                    "logprobs": null,
                    "finish_reason": "stop",
                    "stop_reason": null
                  }],
                  "usage": {
                    "prompt_tokens": 78,
                    "completion_tokens": 42,
                    "total_tokens": 120,
                    "prompt_tokens_details": null
                  },
                  "prompt_logprobs": null
                }
                """;

        LlmChatResponse response = objectMapper.readValue(json, LlmChatResponse.class);

        assertThat(response.object()).isEqualTo("chat.completion");
        assertThat(response.created()).isEqualTo(1753000000L);
        assertThat(response.model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(response.choices().getFirst().message().getReasoningContent())
                .isEqualTo("한국어로 답변하자.");
        assertThat(response.choices().getFirst().finishReason()).isEqualTo("stop");
        assertThat(response.usage().promptTokens()).isEqualTo(78);
        assertThat(response.usage().completionTokens()).isEqualTo(42);
        assertThat(response.usage().totalTokens()).isEqualTo(120);
    }
}
