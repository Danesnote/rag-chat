package com.ragchat.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 하이브리드 검색: 벡터 검색 + 키워드 검색 → RRF 결합 → Cross-encoder 리랭킹.
 *
 * <pre>
 *   질의 ─┬─ 임베딩 → pgvector 코사인 검색 (top vectorTopK) ─┐
 *         └─ 키워드 추출 → ILIKE 검색 (top keywordTopK) ─────┴─ RRF → 상위 rerankCandidates → 리랭커 → topN
 * </pre>
 *
 * <p>리랭커가 꺼져 있거나 호출에 실패하면 RRF 순위 상위 {@code topN} 으로 대체한다.
 * 키워드 검색이 실패해도 벡터 결과만으로 계속 진행한다. (임베딩 실패는 검색 자체가 불가능하므로 예외)</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final ChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final RerankClient rerankClient;

    @Value("${rag.search.vector-top-k:20}")
    private int vectorTopK;
    @Value("${rag.search.keyword-top-k:20}")
    private int keywordTopK;
    @Value("${rag.search.rrf-k:60}")
    private int rrfK;
    /** 리랭커에 넘길 RRF 상위 후보 수 */
    @Value("${rag.search.rerank-candidates:20}")
    private int rerankCandidates;
    /** 최종 반환 건수 */
    @Value("${rag.search.top-n:5}")
    private int topN;
    @Value("${rag.reranker.enabled:true}")
    private boolean rerankerEnabled;

    /**
     * 질의와 관련도가 높은 청크를 상위 {@code topN} 건 돌려준다.
     */
    public List<DocumentChunk> search(String query) {
        float[] queryVector = embeddingClient.embed(query);
        List<DocumentChunk> vectorHits = chunkRepository.searchByVector(queryVector, vectorTopK);

        List<String> patterns = KeywordQuery.likePatterns(query);
        List<DocumentChunk> keywordHits;
        try {
            keywordHits = chunkRepository.searchByKeywords(patterns, keywordTopK);
        } catch (Exception e) {
            log.warn("[Search] 키워드 검색 실패, 벡터 결과만 사용합니다: {}", e.getMessage());
            keywordHits = List.of();
        }

        List<DocumentChunk> fused = RankFusion.fuse(rrfK, vectorHits, keywordHits);
        List<DocumentChunk> candidates = fused.subList(0, Math.min(rerankCandidates, fused.size()));

        log.info("[Search] vector {}건 + keyword {}건(키워드 {}) → RRF {}건 → 리랭크 후보 {}건",
                vectorHits.size(), keywordHits.size(), KeywordQuery.keywords(query),
                fused.size(), candidates.size());

        if (!rerankerEnabled) {
            return List.copyOf(candidates.subList(0, Math.min(topN, candidates.size())));
        }
        try {
            List<DocumentChunk> reranked = rerankClient.rerank(query, candidates, topN);
            if (log.isDebugEnabled()) {
                reranked.forEach(c -> log.debug("[Search] rerank {} score={} {}", c.chunkId(), c.score(), c.title()));
            }
            return reranked;
        } catch (Exception e) {
            log.warn("[Search] 리랭커 호출 실패, RRF 순위로 대체합니다: {}", e.getMessage());
            return List.copyOf(candidates.subList(0, Math.min(topN, candidates.size())));
        }
    }
}
