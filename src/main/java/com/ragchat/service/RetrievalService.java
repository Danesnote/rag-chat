package com.ragchat.service;

import com.ragchat.retrieval.ChunkRepository;
import com.ragchat.retrieval.DocumentChunk;
import com.ragchat.retrieval.HybridSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 검색 방식(모드)에 따라 LLM에 전달할 문서를 확보한다.
 *
 * <ul>
 *   <li>모드1: 하이브리드 검색(벡터+키워드 → RRF → 리랭커) 결과를 그대로 사용</li>
 *   <li>모드2: 리랭커가 고른 청크의 <b>앞/뒤 청크</b>(청크번호 ±1)를 함께 가져와,
 *       검색 결과와 <b>동등한 독립 문서</b>로 제공한다.</li>
 * </ul>
 *
 * <p><b>모드2가 청크를 병합하지 않는 이유.</b> 앞/뒤 청크를 본문에 이어 붙여 하나의 지문으로 만들면
 * 세 청크가 검색 청크의 메타데이터(문서번호 등) 하나를 공유하게 되어, LLM이 앞/뒤 청크의 조문을
 * 검색 청크의 조문으로 잘못 귀속시킨다. 또 어느 부분이 실제 검색 결과이고 어느 부분이 확장분인지
 * 구분할 방법이 없다. 그래서 청크마다 자기 메타데이터를 달고 독립 문서로 내보낸다.</p>
 *
 * <p><b>필드 의미 주의.</b> {@code version} 은 조문번호가 아니라 문서의 <b>버전(개정차수)</b>이고,
 * 조문번호(제N조)는 메타데이터에 없고 본문 텍스트 안에만 존재한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    public static final int MODE_HYBRID = 1;
    public static final int MODE_CHUNK_EXPANSION = 2;

    private final HybridSearchService hybridSearchService;
    private final ChunkRepository chunkRepository;

    /**
     * 문서의 출처 구분. [CONTEXT] 라벨과 화면 배지에 쓰인다.
     */
    public enum Origin {
        /** 리랭커가 직접 고른 검색 결과 */
        HIT,
        /** 검색 결과의 직전 청크 (모드2 확장) */
        PREV,
        /** 검색 결과의 직후 청크 (모드2 확장) */
        NEXT;

        public boolean isNeighbor() {
            return this != HIT;
        }
    }

    /**
     * 근거 후보 문서 한 건. 검색 결과든 인접 청크든 <b>동등한 한 건</b>으로 취급한다.
     *
     * @param chunk 청크 본문과 메타데이터 (화면 표시와 LLM 컨텍스트에 동일하게 사용)
     * @param origin 이 문서가 검색 결과인지 인접 확장인지
     * @param anchorChunkId 인접 청크인 경우 기준이 된 검색 결과의 청크 ID. 검색 결과 자신이면 {@code null}
     */
    public record RetrievalItem(DocumentChunk chunk, Origin origin, String anchorChunkId) {

        public String chunkId() {
            return chunk != null ? chunk.chunkId() : null;
        }
    }

    /**
     * 질의에 대한 근거 후보 문서를 모드에 맞게 확보한다.
     *
     * <p>반환 순서는 리랭커 랭킹을 따르되, 모드2에서는 각 검색 결과 앞뒤에 인접 청크를
     * {@code 앞 → 검색결과 → 뒤} 순으로 끼워 넣어 문맥이 이어지도록 배치한다.
     * 청크 ID 기준으로 중복을 제거하며, 인접 청크가 다른 검색 결과와 겹치면 검색 결과를 우선한다.</p>
     *
     * @param question 검색 질의
     * @param mode 검색 방식(1: 하이브리드, 2: 하이브리드 + 인접 청크 확장)
     * @return 근거 후보 문서 목록
     */
    public List<RetrievalItem> retrieve(String question, int mode) {
        List<DocumentChunk> hits = hybridSearchService.search(question).stream()
                .filter(DocumentChunk::hasContent)
                .toList();

        if (mode != MODE_CHUNK_EXPANSION) {
            log.info("[Retrieval] mode=1 - 검색결과 {}건", hits.size());
            return hits.stream().map(c -> new RetrievalItem(c, Origin.HIT, null)).toList();
        }

        Set<String> hitIds = hits.stream().map(DocumentChunk::chunkId).collect(Collectors.toSet());
        Map<String, DocumentChunk> rangeCache = new HashMap<>();
        LinkedHashMap<String, RetrievalItem> picked = new LinkedHashMap<>();
        int neighborCount = 0;

        for (DocumentChunk hit : hits) {
            if (picked.containsKey(hit.chunkId())) {
                continue;
            }
            // 앞/자기/뒤 세 청크를 한 번에 조회한다.
            for (DocumentChunk c : chunkRepository.findRange(hit.documentId(), hit.chunkNo() - 1, hit.chunkNo() + 1)) {
                rangeCache.putIfAbsent(c.chunkId(), c);
            }
            DocumentChunk prev = rangeCache.get(idOf(hit.documentId(), hit.chunkNo() - 1));
            DocumentChunk next = rangeCache.get(idOf(hit.documentId(), hit.chunkNo() + 1));

            if (addNeighbor(picked, prev, hitIds, Origin.PREV, hit.chunkId())) {
                neighborCount++;
            }
            picked.put(hit.chunkId(), new RetrievalItem(hit, Origin.HIT, null));
            if (addNeighbor(picked, next, hitIds, Origin.NEXT, hit.chunkId())) {
                neighborCount++;
            }
        }

        List<RetrievalItem> items = new ArrayList<>(picked.values());
        log.info("[Retrieval] mode=2 - 총 {}건 (검색결과 {}건 + 인접청크 {}건)", items.size(), hits.size(), neighborCount);
        return items;
    }

    /**
     * 인접 청크를 결과에 담는다. 없거나, 이미 담겼거나, 검색 결과와 겹치면 담지 않는다.
     *
     * @return 실제로 담았으면 {@code true}
     */
    private static boolean addNeighbor(Map<String, RetrievalItem> picked, DocumentChunk neighbor,
                                       Set<String> hitIds, Origin origin, String anchorChunkId) {
        if (neighbor == null || !neighbor.hasContent()) {
            return false;
        }
        String id = neighbor.chunkId();
        if (hitIds.contains(id) || picked.containsKey(id)) {
            return false;
        }
        picked.put(id, new RetrievalItem(neighbor, origin, anchorChunkId));
        return true;
    }

    private static String idOf(long documentId, int chunkNo) {
        return new DocumentChunk(documentId, chunkNo, null, null, null, null, 0).chunkId();
    }
}
