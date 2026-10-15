package com.ragchat.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTests {

    private final TextChunker chunker = new TextChunker(200);

    @Test
    void splitsRuleDocumentByArticleWithChapterPath() {
        String text = """
                제1장 총칙
                제1조(목적) 이 지침은 정보보안에 필요한 사항을 정한다.
                제2조(적용범위) 이 지침은 모든 임직원에게 적용한다.
                제2장 보안관리
                제1절 계정
                제3조(비밀번호) 비밀번호는 90일마다 변경한다.
                제2조에 따른 적용 대상은 예외 없이 준수한다.
                """;

        List<String> chunks = chunker.split(text);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).startsWith("제1장 총칙\n제1조(목적)");
        assertThat(chunks.get(1)).startsWith("제1장 총칙\n제2조(적용범위)");
        // 본문 속 "제2조에 따른 ..." 은 조문 제목이 아니므로 제3조 청크에 남는다.
        assertThat(chunks.get(2))
                .startsWith("제2장 보안관리 제1절 계정\n제3조(비밀번호)")
                .contains("제2조에 따른 적용 대상");
    }

    @Test
    void addendaResetsChapterPath() {
        String text = """
                제3장 특별휴가
                제7조(병가) 연간 30일 이내의 병가를 사용할 수 있다.

                부칙
                제1조(시행일) 이 규정은 2025년 1월 1일부터 시행한다.
                """;

        List<String> chunks = chunker.split(text);

        assertThat(chunks).containsExactly(
                "제3장 특별휴가\n제7조(병가) 연간 30일 이내의 병가를 사용할 수 있다.",
                "부칙\n제1조(시행일) 이 규정은 2025년 1월 1일부터 시행한다.");
    }

    @Test
    void longArticleContinuationsRepeatTheArticleHeading() {
        String sentence = "출장자는 귀임 후 7일 이내에 증빙서류를 첨부하여 여비를 정산하여야 한다. ";
        String text = "제5조(여비 정산)\n" + sentence.repeat(12);

        List<String> chunks = chunker.split(text);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(0)).startsWith("제5조(여비 정산)\n");
        for (String c : chunks.subList(1, chunks.size())) {
            assertThat(c).startsWith("제5조(여비 정산) (계속)\n");
        }
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(200));
    }

    @Test
    void markdownHeadingsAreBoundaries() {
        String text = """
                # 재택근무 안내
                재택근무는 주 2회까지 가능하다.

                ## 신청 방법
                전날까지 팀장 승인을 받는다.
                """;

        List<String> chunks = chunker.split(text);

        assertThat(chunks).containsExactly(
                "# 재택근무 안내\n재택근무는 주 2회까지 가능하다.",
                "## 신청 방법\n전날까지 팀장 승인을 받는다.");
    }

    @Test
    void plainTextPacksParagraphsUpToLimit() {
        String paragraph = "가".repeat(80);
        String text = String.join("\n\n", paragraph, paragraph, paragraph);

        List<String> chunks = chunker.split(text);

        // 80 + 1 + 80 = 161 <= 200 이므로 두 문단이 한 청크, 나머지 하나가 다음 청크
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(161);
    }

    @Test
    void blankInputProducesNoChunks() {
        assertThat(chunker.split("  \n ")).isEmpty();
        assertThat(chunker.split(null)).isEmpty();
    }
}
