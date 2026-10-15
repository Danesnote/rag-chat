package com.ragchat.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordQueryTests {

    @Test
    void stripsParticlesAndQuestionWords() {
        assertThat(KeywordQuery.keywords("연차휴가는 어떻게 산정되나요?"))
                .containsExactly("연차휴가", "산정");
    }

    @Test
    void keepsShortStemsInsteadOfOverStripping() {
        // "기한" 의 "한" 을 떼면 한 글자가 남으므로 떼지 않는다.
        assertThat(KeywordQuery.keywords("신청 기한")).containsExactly("신청", "기한");
    }

    @Test
    void removesDuplicatesAndLimitsCount() {
        assertThat(KeywordQuery.keywords("출장 출장 출장비 여비 숙박비 식비 교통비 일비 정산 증빙 영수증"))
                .hasSize(KeywordQuery.MAX_KEYWORDS)
                .startsWith("출장", "출장비");
    }

    @Test
    void symbolsNeverReachLikePatterns() {
        // 기호는 어절 경계로 취급되므로 LIKE 와일드카드(% _)가 패턴에 섞이지 않는다.
        assertThat(KeywordQuery.likePatterns("목표 100%_달성"))
                .containsExactly("%목표%", "%100%", "%달성%");
    }

    @Test
    void returnsEmptyForBlankOrNull() {
        assertThat(KeywordQuery.keywords(null)).isEmpty();
        assertThat(KeywordQuery.likePatterns("  ?! ")).isEmpty();
    }
}
