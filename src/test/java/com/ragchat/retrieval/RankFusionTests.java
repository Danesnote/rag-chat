package com.ragchat.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RankFusionTests {

    private static DocumentChunk chunk(long doc, int no) {
        return new DocumentChunk(doc, no, "문서" + doc, null, null, "본문", 0.9);
    }

    @Test
    void chunkFoundByBothRetrieversRanksFirst() {
        List<DocumentChunk> vector = List.of(chunk(1, 0), chunk(2, 0), chunk(3, 0));
        List<DocumentChunk> keyword = List.of(chunk(3, 0), chunk(4, 0));

        List<DocumentChunk> fused = RankFusion.fuse(60, vector, keyword);

        // 2_0000 과 4_0000 은 둘 다 1/(60+2) 로 동점이며, 먼저 등장한 순서를 유지한다.
        assertThat(fused).extracting(DocumentChunk::chunkId)
                .containsExactly("3_0000", "1_0000", "2_0000", "4_0000");
        // 3_0000: 1/(60+3) + 1/(60+1)
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 63 + 1.0 / 61, within(1e-12));
    }

    @Test
    void emptyListsAreIgnored() {
        List<DocumentChunk> fused = RankFusion.fuse(60, List.of(chunk(1, 2)), List.of());

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).chunkId()).isEqualTo("1_0002");
    }
}
