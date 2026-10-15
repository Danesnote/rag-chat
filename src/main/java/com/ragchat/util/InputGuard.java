package com.ragchat.util;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사용자 입력(질문, 대화방 제목, 검색 질의)에 HTML 태그나 스크립트가 섞여 있는지 판정하는 유틸리티.
 *
 * <p>출력 쪽은 이미 이스케이프/DOMPurify 로 막고 있지만, 공격 문자열이 DB·검색엔진·LLM 까지
 * 흘러가지 않도록 입력 단계에서 한 번 더 걸러낸다. (방어 계층 추가)
 *
 * <p>허용 문자만 통과시키는 방식은 '3억 &lt; 금액' 같은 정상 질문까지 막으므로 쓰지 않는다.
 * 브라우저가 실제로 HTML 로 해석하는 모양만 차단한다.
 * <ul>
 *   <li>{@code <} 바로 뒤에 영문자 / {@code /} / {@code !} / {@code ?} : {@code <img}, {@code </div}, {@code <!--}, {@code <?xml}
 *       ({@code < 3억}, {@code <5억}, {@code <규정>} 은 태그로 해석되지 않으므로 통과)</li>
 *   <li>스크립트 스킴 : {@code javascript:}, {@code vbscript:}, {@code data:text/html}</li>
 * </ul>
 *
 * <p>우회 입력을 잡기 위해 판정 전에 정규화한다.
 * 전각 문자({@code ＜img}), HTML 엔티티({@code &lt;img}, {@code &#60;img}, {@code &#x3c;img}),
 * URL 인코딩({@code %3Cimg}, 이중 인코딩 포함).
 *
 * <p>※ 프론트엔드 {@code appUtil.containsMarkup()} (static/js/common/app-util.js) 과 판정 규칙을 맞춰 둔다.
 */
public final class InputGuard {

    /** 차단 시 사용자에게 보여줄 안내 문구 */
    public static final String REJECT_MESSAGE = "HTML 태그나 스크립트가 포함된 내용은 입력할 수 없습니다.";

    /** 태그 시작 */
    private static final Pattern TAG = Pattern.compile("<[a-zA-Z/!?]");

    /** 스크립트 스킴. 브라우저는 URL 안의 공백·제어문자를 무시하므로 공백을 제거한 문자열에 적용한다. */
    private static final Pattern SCHEME = Pattern.compile("(?i)(javascript|vbscript|livescript):|data:text/html");

    /** 공백·제어문자 (java\tscript: 같은 우회 대응) */
    private static final Pattern WHITESPACE_OR_CONTROL = Pattern.compile("[\\s\\p{Cntrl}]");

    /** URL 인코딩된 ASCII 문자 (%3C 등). 한글 등 멀티바이트는 판정에 필요 없으므로 다루지 않는다. */
    private static final Pattern PERCENT_ASCII = Pattern.compile("%([0-7][0-9a-fA-F])");

    /** 숫자 엔티티 (&#60; &#x3c; — 세미콜론 생략형 포함) */
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#([xX]?)([0-9a-fA-F]{1,7});?");

    /** 판정에 필요한 이름 엔티티 */
    private static final Pattern NAMED_ENTITY = Pattern.compile("(?i)&(lt|gt|colon|sol|tab|newline);?");

    /** 인코딩을 몇 겹까지 풀지 (%253C → %3C → < 같은 이중 인코딩 대응) */
    private static final int MAX_DECODE_PASSES = 3;

    private InputGuard() {
    }

    /**
     * 입력에 HTML 태그나 스크립트 스킴이 포함되어 있는지 판정한다.
     *
     * @param input 사용자 입력. {@code null}/공백이면 {@code false}
     * @return 차단 대상이면 {@code true}
     */
    public static boolean containsMarkup(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }

        String normalized = normalize(input);
        if (TAG.matcher(normalized).find()) {
            return true;
        }
        String compact = WHITESPACE_OR_CONTROL.matcher(normalized).replaceAll("");
        return SCHEME.matcher(compact).find();
    }

    /** 전각 → 반각, 엔티티·URL 인코딩 해제. 더 이상 바뀌지 않거나 최대 횟수에 도달하면 멈춘다. */
    private static String normalize(String input) {
        String s = Normalizer.normalize(input, Normalizer.Form.NFKC);
        for (int i = 0; i < MAX_DECODE_PASSES; i++) {
            String decoded = decodeNamedEntities(decodeNumericEntities(decodePercentAscii(s)));
            if (decoded.equals(s)) {
                break;
            }
            s = Normalizer.normalize(decoded, Normalizer.Form.NFKC);
        }
        return s;
    }

    private static String decodePercentAscii(String s) {
        Matcher m = PERCENT_ASCII.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            char ch = (char) Integer.parseInt(m.group(1), 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(ch)));
        }
        return m.appendTail(sb).toString();
    }

    private static String decodeNumericEntities(String s) {
        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            boolean hex = !m.group(1).isEmpty();
            String digits = m.group(2);
            String replacement;
            try {
                int cp = Integer.parseInt(digits, hex ? 16 : 10);
                replacement = Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : m.group();
            } catch (NumberFormatException e) {
                // 10진 엔티티에 a-f 가 섞인 경우 등: 엔티티가 아니므로 원문 유지
                replacement = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        return m.appendTail(sb).toString();
    }

    private static String decodeNamedEntities(String s) {
        Matcher m = NAMED_ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = switch (m.group(1).toLowerCase()) {
                case "lt" -> "<";
                case "gt" -> ">";
                case "colon" -> ":";
                case "sol" -> "/";
                case "tab" -> "\t";
                case "newline" -> "\n";
                default -> m.group();
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        return m.appendTail(sb).toString();
    }
}
