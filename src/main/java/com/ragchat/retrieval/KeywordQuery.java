package com.ragchat.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 질의문을 키워드 검색용 ILIKE 패턴 목록으로 바꾼다.
 *
 * <p>형태소 분석기 없이 동작하는 경량 방식이다. 공백·기호로 나눈 어절에서 흔한 조사·어미를 떼어
 * 어간에 가까운 형태를 만들고, 의미가 약한 의문 표현을 제외한다.
 * 예) {@code "연차휴가는 어떻게 산정되나요?"} → {@code ["%연차휴가%", "%산정%"]}</p>
 *
 * <p>정밀한 형태소 분석이 필요하면 이 클래스만 교체하면 된다. 의미 검색은 벡터 검색이 담당하므로
 * 키워드 검색은 "정확한 용어가 들어간 청크를 놓치지 않는 것"을 목표로 한다.</p>
 */
public final class KeywordQuery {

    /** 패턴으로 쓸 최대 키워드 수. 너무 많으면 ILIKE 비용이 커지고 점수가 희석된다. */
    static final int MAX_KEYWORDS = 8;

    private static final int MIN_LENGTH = 2;

    /** 어절 끝에서 떼어낼 조사·어미. 긴 것부터 검사한다. */
    private static final List<String> SUFFIXES = List.of(
            "되나요", "하나요", "인가요", "있나요", "없나요", "됩니까", "합니까", "입니까",
            "에서는", "으로는", "에서", "으로", "에게", "까지", "부터", "보다", "처럼", "이나", "이란", "이며",
            "되는", "하는", "되며", "하며", "하고", "되고", "된", "할", "될",
            "은", "는", "이", "가", "을", "를", "의", "에", "와", "과", "도", "만", "로", "요"
    );

    /** 검색어로서 변별력이 없는 표현 */
    private static final Set<String> STOPWORDS = Set.of(
            "어떻게", "무엇", "무엇인가", "어떤", "언제", "얼마", "얼마나", "어디", "누구", "왜",
            "관련", "대한", "대해", "대해서", "경우", "방법", "내용", "알려줘", "알려주세요", "궁금합니다", "있는지"
    );

    private KeywordQuery() {
    }

    /**
     * 질의에서 키워드를 추출한다. (중복 제거, 등장 순서 유지)
     */
    public static List<String> keywords(String query) {
        Set<String> result = new LinkedHashSet<>();
        if (query == null) {
            return List.of();
        }
        for (String raw : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            String token = stripSuffix(raw);
            if (token.length() >= MIN_LENGTH && !STOPWORDS.contains(token) && !STOPWORDS.contains(raw)) {
                result.add(token);
            }
            if (result.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        return new ArrayList<>(result);
    }

    /**
     * 키워드를 부분일치 ILIKE 패턴으로 바꾼다. 키워드 안의 {@code % _ \} 는 이스케이프한다.
     */
    public static List<String> likePatterns(String query) {
        return keywords(query).stream()
                .map(k -> "%" + k.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                .toList();
    }

    private static String stripSuffix(String token) {
        for (String suffix : SUFFIXES) {
            // 떼고 나서도 두 글자 이상 남을 때만 뗀다. ("기한" 의 "한" 같은 오탐 방지)
            if (token.endsWith(suffix) && token.length() - suffix.length() >= MIN_LENGTH) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        return token;
    }
}
