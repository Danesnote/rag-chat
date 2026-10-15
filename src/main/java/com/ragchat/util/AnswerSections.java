package com.ragchat.util;

import java.util.regex.Pattern;

/**
 * LLM 답변에서 화면 표시용 부가 영역({@code [출처]}, {@code [연관질문]})을 다루는 유틸리티.
 *
 * <p>이 영역들은 사용자에게 보여주고 DB에 남기기 위한 것이며, <b>다음 턴의 입력으로 되돌려 넣을 이유가 없다.</b>
 * 그대로 이력에 실으면 두 가지 문제가 생긴다.
 * <ul>
 *   <li>턴마다 200~400 토큰씩 입력 예산을 잠식한다.</li>
 *   <li>모델이 이전 턴의 [출처] 목록을 현재 [CONTEXT]에 없는데도 그대로 베껴 쓴다.
 *       (시스템 프롬프트가 이를 금지하는 규칙을 여러 줄 쓰고 있으나, 애초에 넣지 않는 편이 확실하다)</li>
 * </ul>
 */
public final class AnswerSections {

    /** 잘라낼 영역의 시작 표지. 마크다운 강조가 붙은 형태와 아닌 형태를 모두 본다. */
    private static final String[] MARKERS = {
            "**[출처]**", "[출처]", "**[연관질문]**", "[연관질문]"
    };

    /**
     * 답변의 문서 번호 라벨. 한글 {@code [문서N]} 과, 모델이 영어로 바꿔 쓴 변형
     * ({@code [document1]}, {@code [Doc 1]}, {@code [doc-1]} 등)을 모두 잡는다.
     * 추론 모델이 영어로 추론하다가 그 표기가 최종 답변에 새어 나오는 경우가 있다.
     * 그룹 1은 번호다.
     */
    public static final Pattern CITATION_LABEL_PATTERN = Pattern.compile(
            "\\[\\s*(?:문서|document|doc)\\s*[-_#]?\\s*(\\d{1,2})\\s*]", Pattern.CASE_INSENSITIVE);

    private AnswerSections() {
    }

    /**
     * 문서 번호 라벨을 한글 표준형 {@code [문서N]} 으로 통일한다.
     * 화면 표시·DB 저장·다음 턴 이력이 모두 같은 표기를 쓰도록 저장 전에 적용한다.
     *
     * @param answer 답변 원문. {@code null} 이면 {@code null} 을 그대로 돌려준다.
     * @return 라벨이 {@code [문서N]} 으로 통일된 답변
     */
    public static String normalizeCitationLabels(String answer) {
        if (answer == null) {
            return null;
        }
        return CITATION_LABEL_PATTERN.matcher(answer).replaceAll("[문서$1]");
    }

    /**
     * 답변 본문에서 {@code [출처]} / {@code [연관질문]} 영역을 제거한다.
     * (둘 중 먼저 나오는 지점부터 끝까지 잘라낸다)
     *
     * @param answer 답변 원문. {@code null} 이면 {@code null} 을 그대로 돌려준다.
     * @return 부가 영역을 제거하고 양끝을 정리한 본문
     */
    public static String strip(String answer) {
        if (answer == null) {
            return null;
        }
        int cut = answer.length();
        for (String marker : MARKERS) {
            int index = answer.indexOf(marker);
            if (index >= 0 && index < cut) {
                cut = index;
            }
        }
        return answer.substring(0, cut).trim();
    }
}
