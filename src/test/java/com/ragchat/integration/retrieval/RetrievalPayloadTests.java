package com.ragchat.integration.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragchat.dto.integration.embedding.EmbeddingRequest;
import com.ragchat.dto.integration.embedding.EmbeddingResponse;
import com.ragchat.dto.integration.rerank.RerankRequest;
import com.ragchat.dto.integration.rerank.RerankResult;
import com.ragchat.retrieval.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 임베딩(OpenAI 호환) · 리랭커(TEI) API 페이로드 계약 테스트.
 */
class RetrievalPayloadTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesOpenAiCompatibleEmbeddingRequest() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(
                new EmbeddingRequest("BAAI/bge-m3", List.of("연차휴가", "출장비"))));

        assertThat(json.get("model").asText()).isEqualTo("BAAI/bge-m3");
        assertThat(json.get("input")).hasSize(2);
    }

    @Test
    void parsesEmbeddingResponseIgnoringExtraFields() throws Exception {
        EmbeddingResponse response = objectMapper.readValue("""
                {
                  "object": "list",
                  "model": "BAAI/bge-m3",
                  "data": [
                    {"object": "embedding", "index": 1, "embedding": [0.3, 0.4]},
                    {"object": "embedding", "index": 0, "embedding": [0.1, 0.2]}
                  ],
                  "usage": {"prompt_tokens": 4, "total_tokens": 4}
                }
                """, EmbeddingResponse.class);

        assertThat(response.data()).hasSize(2);
        assertThat(response.data().get(1).index()).isZero();
        assertThat(response.data().get(1).embedding()).containsExactly(0.1f, 0.2f);
    }

    @Test
    void serializesTeiRerankRequestAndParsesResult() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(
                new RerankRequest("연차 산정", List.of("본문1", "본문2"), true)));
        assertThat(json.get("query").asText()).isEqualTo("연차 산정");
        assertThat(json.get("texts")).hasSize(2);
        assertThat(json.get("truncate").asBoolean()).isTrue();

        List<RerankResult> results = objectMapper.readValue(
                "[{\"index\":1,\"score\":0.98},{\"index\":0,\"score\":0.12}]",
                new TypeReference<>() {});
        assertThat(results).extracting(RerankResult::index).containsExactly(1, 0);
    }

    @Test
    void formatsPgvectorLiteral() {
        assertThat(EmbeddingClient.toVectorLiteral(new float[]{0.5f, -1.0f, 0.25f}))
                .isEqualTo("[0.5,-1.0,0.25]");
    }
}
