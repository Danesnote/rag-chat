package com.ragchat.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.ragchat.dto.integration.rerank.RerankRequest;
import com.ragchat.dto.integration.rerank.RerankResult;
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
 * Cross-encoder 리랭커 클라이언트.
 *
 * <p>기본 구성은 HuggingFace Text Embeddings Inference 로 서빙한 {@code BAAI/bge-reranker-v2-m3} 이며,
 * TEI 의 {@code POST /rerank} 형식({@code {query, texts}} → {@code [{index, score}]})을 사용한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankClient {

    private static final TypeReference<List<RerankResult>> RESULT_TYPE = new TypeReference<>() {};

    private final HttpUtils httpUtils;

    @Value("${rag.reranker.url}")
    private String url;
    @Value("${rag.reranker.api-key:}")
    private String apiKey;

    /**
     * 후보 청크를 질의와의 관련도 순으로 재정렬해 상위 {@code topN} 건을 돌려준다.
     * 반환되는 청크의 {@code score} 는 리랭커 점수로 바뀐다.
     *
     * @throws IllegalStateException 리랭커 응답이 비어 있는 경우 (호출부에서 폴백 처리)
     */
    public List<DocumentChunk> rerank(String query, List<DocumentChunk> candidates, int topN) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> texts = candidates.stream()
                .map(c -> c.title() == null ? c.content() : c.title() + "\n" + c.content())
                .toList();

        List<RerankResult> results = httpUtils.post(
                url,
                (apiKey == null || apiKey.isBlank()) ? Map.of() : Map.of("Authorization", "Bearer " + apiKey),
                new RerankRequest(query, texts, true),
                RESULT_TYPE
        );
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("리랭커 응답이 비어 있습니다.");
        }

        List<RerankResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparingDouble(RerankResult::score).reversed());

        List<DocumentChunk> reranked = new ArrayList<>();
        for (RerankResult r : sorted) {
            if (r.index() < 0 || r.index() >= candidates.size()) {
                continue;
            }
            reranked.add(candidates.get(r.index()).withScore(r.score()));
            if (reranked.size() >= topN) {
                break;
            }
        }
        return reranked;
    }
}
