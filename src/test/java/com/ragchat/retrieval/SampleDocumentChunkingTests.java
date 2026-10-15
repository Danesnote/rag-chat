package com.ragchat.retrieval;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * samples/ 의 예시 문서가 기본 설정(800자)으로 의도대로 나뉘는지 확인한다.
 */
class SampleDocumentChunkingTests {

    private final TextChunker chunker = new TextChunker(800);

    @ParameterizedTest
    @ValueSource(strings = {"leave-policy.txt", "travel-expense.txt", "information-security.txt", "corporate-card.txt"})
    void everyRuleChunkStartsWithItsArticleHeading(String file) throws Exception {
        List<String> chunks = chunker.split(Files.readString(Path.of("samples", file), StandardCharsets.UTF_8));

        assertThat(chunks).isNotEmpty();
        for (String chunk : chunks) {
            // 첫 줄은 장·절 경로 또는 "부칙"일 수 있으므로, 첫 두 줄 안에 조문 표기가 있어야 한다.
            String head = String.join("\n", chunk.lines().limit(2).toList());
            assertThat(head).as(file + " 청크 첫머리: " + head).containsPattern("제\\d+조\\(");
            assertThat(chunk.length()).isLessThanOrEqualTo(800);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"remote-work.md"})
    void markdownChunksStartWithHeading(String file) throws Exception {
        List<String> chunks = chunker.split(Files.readString(Path.of("samples", file), StandardCharsets.UTF_8));

        assertThat(chunks).hasSize(5);
        assertThat(chunks).allSatisfy(c -> assertThat(c).startsWith("#"));
    }
}
