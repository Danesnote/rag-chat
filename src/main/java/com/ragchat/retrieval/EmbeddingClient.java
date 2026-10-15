package com.ragchat.retrieval;

import com.ragchat.dto.integration.embedding.EmbeddingRequest;
import com.ragchat.dto.integration.embedding.EmbeddingResponse;
import com.ragchat.util.HttpUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 호환 {@code /v1/embeddings} API 클라이언트.
 *
 * <p>기본 구성은 HuggingFace Text Embeddings Inference 로 서빙한 {@code BAAI/bge-m3}(1024차원) 이다.
 * 같은 형식을 지원하는 vLLM, Ollama 로 바꿔도 코드는 그대로 동작한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    private final HttpUtils httpUtils;

    @Value("${rag.embedding.url}")
    private String url;
    @Value("${rag.embedding.model}")
    private String model;
    @Value("${rag.embedding.api-key:}")
    private String apiKey;
    /** 스키마의 vector(N) 차원. 응답 차원이 다르면 저장·검색이 실패하므로 미리 검사한다. */
    @Value("${rag.embedding.dimension:1024}")
    private int dimension;
    /** 한 번의 요청에 담을 최대 문장 수. 서버의 max-client-batch-size 보다 작아야 한다. */
    @Value("${rag.embedding.batch-size:8}")
    private int batchSize;

    /**
     * 문자열 하나를 임베딩한다.
     */
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    /**
     * 여러 문자열을 배치로 나눠 임베딩한다. 반환 순서는 입력 순서와 같다.
     *
     * @throws IllegalStateException 응답 건수나 차원이 맞지 않는 경우
     */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
            vectors.addAll(requestBatch(batch));
        }
        return vectors;
    }

    private List<float[]> requestBatch(List<String> batch) {
        EmbeddingResponse response = httpUtils.post(
                url,
                authHeaders(),
                new EmbeddingRequest(model, batch),
                EmbeddingResponse.class
        );
        if (response == null || response.data() == null || response.data().size() != batch.size()) {
            throw new IllegalStateException("임베딩 응답 건수가 요청과 다릅니다. (요청 " + batch.size() + "건)");
        }

        List<EmbeddingResponse.Item> items = new ArrayList<>(response.data());
        items.sort(Comparator.comparingInt(EmbeddingResponse.Item::index));

        List<float[]> vectors = new ArrayList<>(items.size());
        for (EmbeddingResponse.Item item : items) {
            if (item.embedding() == null || item.embedding().length != dimension) {
                throw new IllegalStateException("임베딩 차원이 설정과 다릅니다. expected=" + dimension
                        + ", actual=" + (item.embedding() == null ? 0 : item.embedding().length)
                        + " (rag.embedding.dimension 과 스키마의 vector(N) 을 모델에 맞추세요)");
            }
            vectors.add(item.embedding());
        }
        return vectors;
    }

    private Map<String, String> authHeaders() {
        return (apiKey == null || apiKey.isBlank()) ? Map.of() : Map.of("Authorization", "Bearer " + apiKey);
    }

    /**
     * pgvector 입력 리터럴로 변환한다. 예) {@code [0.12,-0.03,...]}
     */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
