package com.ragchat.dto.integration.embedding;

import java.util.List;

/**
 * OpenAI 호환 {@code /v1/embeddings} 요청 DTO.
 *
 * <p>HuggingFace Text Embeddings Inference, vLLM, Ollama 모두 같은 형식을 받는다.</p>
 *
 * @param model 임베딩 모델 식별자
 * @param input 임베딩할 문자열 목록. 응답의 {@code index} 가 이 목록의 순서와 대응한다.
 */
public record EmbeddingRequest(
        String model,
        List<String> input
) {
}
