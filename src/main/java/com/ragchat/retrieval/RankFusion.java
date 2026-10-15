package com.ragchat.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion(RRF).
 *
 * <p>벡터 유사도와 키워드 점수는 척도가 달라 그대로 더할 수 없다. RRF 는 점수 대신 <b>순위</b>만 써서
 * {@code score(d) = Σ 1 / (k + rank_i(d))} 로 합친다. 두 목록에 모두 등장한 청크가 위로 올라온다.</p>
 */
public final class RankFusion {

    private RankFusion() {
    }

    /**
     * 여러 순위 목록을 RRF 로 합친다. 같은 청크({@link DocumentChunk#chunkId()})는 한 건으로 합쳐지며,
     * 반환되는 청크의 {@code score} 는 RRF 점수다.
     *
     * @param k 순위 완화 상수 (보통 60). 클수록 하위 순위의 기여가 상대적으로 커진다.
     * @param rankedLists 각 검색기의 결과 (앞쪽이 상위)
     * @return RRF 점수 내림차순 목록
     */
    @SafeVarargs
    public static List<DocumentChunk> fuse(int k, List<DocumentChunk>... rankedLists) {
        Map<String, DocumentChunk> chunks = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();

        for (List<DocumentChunk> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                DocumentChunk chunk = list.get(rank);
                String id = chunk.chunkId();
                chunks.putIfAbsent(id, chunk);
                scores.merge(id, 1.0 / (k + rank + 1), Double::sum);
            }
        }

        List<DocumentChunk> fused = new ArrayList<>(chunks.size());
        for (Map.Entry<String, DocumentChunk> e : chunks.entrySet()) {
            fused.add(e.getValue().withScore(scores.get(e.getKey())));
        }
        fused.sort(Comparator.comparingDouble(DocumentChunk::score).reversed());
        return fused;
    }
}
