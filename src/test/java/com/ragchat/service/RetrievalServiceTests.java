package com.ragchat.service;

import com.ragchat.retrieval.ChunkRepository;
import com.ragchat.retrieval.DocumentChunk;
import com.ragchat.retrieval.HybridSearchService;
import com.ragchat.service.RetrievalService.Origin;
import com.ragchat.service.RetrievalService.RetrievalItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

class RetrievalServiceTests {

    private final HybridSearchService search = mock(HybridSearchService.class);
    private final ChunkRepository repository = mock(ChunkRepository.class);
    private final RetrievalService service = new RetrievalService(search, repository);

    private static DocumentChunk chunk(long doc, int no) {
        return new DocumentChunk(doc, no, "문서" + doc, "1", "2025-01-01", "본문 " + doc + "-" + no, 0.5);
    }

    @Test
    void hybridModeReturnsHitsOnly() {
        when(search.search("q")).thenReturn(List.of(chunk(1, 3), chunk(2, 0)));

        List<RetrievalItem> items = service.retrieve("q", RetrievalService.MODE_HYBRID);

        assertThat(items).extracting(RetrievalItem::chunkId).containsExactly("1_0003", "2_0000");
        assertThat(items).allMatch(it -> it.origin() == Origin.HIT);
        verify(repository, never()).findRange(anyLong(), anyInt(), anyInt());
    }

    @Test
    void chunkExpansionPlacesNeighborsAroundEachHitWithoutDuplicates() {
        // 1_0003 과 1_0004 가 모두 검색 결과다. 1_0004 는 1_0003 의 "뒤" 이웃이지만 검색 결과로만 담겨야 한다.
        when(search.search("q")).thenReturn(List.of(chunk(1, 3), chunk(1, 4)));
        when(repository.findRange(eq(1L), eq(2), eq(4))).thenReturn(List.of(chunk(1, 2), chunk(1, 3), chunk(1, 4)));
        when(repository.findRange(eq(1L), eq(3), eq(5))).thenReturn(List.of(chunk(1, 3), chunk(1, 4), chunk(1, 5)));

        List<RetrievalItem> items = service.retrieve("q", RetrievalService.MODE_CHUNK_EXPANSION);

        assertThat(items).extracting(RetrievalItem::chunkId)
                .containsExactly("1_0002", "1_0003", "1_0004", "1_0005");
        assertThat(items).extracting(RetrievalItem::origin)
                .containsExactly(Origin.PREV, Origin.HIT, Origin.HIT, Origin.NEXT);
        assertThat(items.get(0).anchorChunkId()).isEqualTo("1_0003");
        assertThat(items.get(3).anchorChunkId()).isEqualTo("1_0004");
    }

    @Test
    void firstChunkHasNoPreviousNeighbor() {
        when(search.search("q")).thenReturn(List.of(chunk(7, 0)));
        when(repository.findRange(eq(7L), eq(-1), eq(1))).thenReturn(List.of(chunk(7, 0), chunk(7, 1)));

        List<RetrievalItem> items = service.retrieve("q", RetrievalService.MODE_CHUNK_EXPANSION);

        assertThat(items).extracting(RetrievalItem::chunkId).containsExactly("7_0000", "7_0001");
        assertThat(items).extracting(RetrievalItem::origin).containsExactly(Origin.HIT, Origin.NEXT);
    }
}
