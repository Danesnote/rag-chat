package com.ragchat.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 문서 본문을 검색 단위(청크)로 나눈다.
 *
 * <p><b>조문형 문서</b>(규정·지침처럼 {@code 제N조} 로 구성된 문서)는 조문 하나를 청크 하나로 만든다.
 * 청크 앞에는 소속 장·절 제목을 붙이고, 조문이 {@code maxChars} 를 넘어 여러 청크로 나뉘면
 * 이어지는 청크 앞에 {@code "제N조(제목) (계속)"} 을 붙인다. 그래서 <b>모든 청크의 첫머리에 자기 조문 표기가 있다</b>.
 * 시스템 프롬프트는 이 성질을 이용해 LLM 이 본문 첫머리의 조문 표기로 출처를 적게 한다.</p>
 *
 * <p>Markdown 문서는 {@code #} 제목을 조문과 같은 경계로 취급한다.
 * 그 밖의 문서는 빈 줄로 구분한 문단을 {@code maxChars} 까지 이어 붙인다.</p>
 */
public final class TextChunker {

    private static final Pattern CHAPTER = Pattern.compile("^제\\s*\\d+\\s*장(?:\\s.*)?$");
    private static final Pattern SECTION = Pattern.compile("^제\\s*\\d+\\s*절(?:\\s.*)?$");
    /** "제5조", "제5조(목적) ..." 는 조문 제목이고, "제5조에 따라 ..." 같은 본문 속 인용은 제목이 아니다. */
    private static final Pattern ARTICLE = Pattern.compile("^제\\s*\\d+\\s*조(?:의\\s*\\d+)?\\s*(?:\\([^)]*\\).*)?$");
    private static final Pattern ADDENDA = Pattern.compile("^부\\s*칙.*$");
    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+.+$");

    private final int maxChars;

    /**
     * @param maxChars 청크 최대 글자 수 (한국어 기준 대략 같은 수의 토큰)
     */
    public TextChunker(int maxChars) {
        if (maxChars < 100) {
            throw new IllegalArgumentException("maxChars must be >= 100");
        }
        this.maxChars = maxChars;
    }

    /**
     * 본문을 청크 목록으로 나눈다. 빈 청크는 만들지 않는다.
     */
    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        boolean structured = false;
        for (String line : lines) {
            String t = line.trim();
            if (ARTICLE.matcher(t).matches() || MD_HEADING.matcher(t).matches()) {
                structured = true;
                break;
            }
        }
        return structured ? splitStructured(lines) : splitParagraphs(normalized);
    }

    // ── 조문형 / Markdown ────────────────────────────────

    private List<String> splitStructured(String[] lines) {
        List<String> chunks = new ArrayList<>();
        String chapter = null;
        String section = null;
        String heading = null;               // 현재 조문(또는 # 제목) 첫 줄
        StringBuilder body = new StringBuilder();
        StringBuilder preamble = new StringBuilder();   // 첫 조문 이전의 본문(목적·총칙 서문 등)

        for (String rawLine : lines) {
            String line = rawLine.trim();
            // 부칙은 본칙과 조문 번호가 다시 시작하므로 장(章)처럼 취급해 경로를 새로 잡는다.
            boolean isChapter = CHAPTER.matcher(line).matches() || ADDENDA.matcher(line).matches();
            boolean isSection = SECTION.matcher(line).matches();
            boolean isArticle = ARTICLE.matcher(line).matches() || MD_HEADING.matcher(line).matches();

            if (isChapter || isSection || isArticle) {
                flushArticle(chunks, chapter, section, heading, body);
                heading = null;
                body.setLength(0);
            }
            if (isChapter) {
                chapter = line;
                section = null;
                continue;
            }
            if (isSection) {
                section = line;
                continue;
            }
            if (isArticle) {
                heading = line;
                continue;
            }
            if (heading == null) {
                appendLine(preamble, rawLine);
            } else {
                appendLine(body, rawLine);
            }
        }
        flushArticle(chunks, chapter, section, heading, body);

        if (!preamble.toString().isBlank()) {
            chunks.addAll(0, splitParagraphs(preamble.toString()));
        }
        return chunks;
    }

    private void flushArticle(List<String> chunks, String chapter, String section, String heading, StringBuilder body) {
        if (heading == null && body.toString().isBlank()) {
            return;
        }
        String path = joinNonBlank(chapter, section);
        String head = heading == null ? "" : heading;
        String prefix = path.isEmpty() ? "" : path + "\n";

        String bodyText = body.toString().strip();
        String whole = (prefix + head + (bodyText.isEmpty() ? "" : "\n" + bodyText)).strip();
        if (whole.length() <= maxChars) {
            chunks.add(whole);
            return;
        }

        // 조문이 길면 나눈다. 이어지는 조각에는 조문 표기를 다시 달아 출처를 잃지 않게 한다.
        String firstPrefix = (prefix + head).strip();
        String continuedPrefix = (prefix + head + (head.isEmpty() ? "" : " (계속)")).strip();
        int budget = Math.max(100, maxChars - continuedPrefix.length() - 1);

        List<String> pieces = packPieces(splitIntoUnits(bodyText, budget), budget);
        for (int i = 0; i < pieces.size(); i++) {
            String p = i == 0 ? firstPrefix : continuedPrefix;
            chunks.add((p.isEmpty() ? pieces.get(i) : p + "\n" + pieces.get(i)).strip());
        }
    }

    // ── 일반 문단 ────────────────────────────────────────

    private List<String> splitParagraphs(String text) {
        List<String> units = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String p = paragraph.strip();
            if (!p.isEmpty()) {
                units.addAll(splitIntoUnits(p, maxChars));
            }
        }
        return packPieces(units, maxChars);
    }

    /**
     * 텍스트를 {@code limit} 이하 조각으로 나눈다. 줄 → 문장 → 글자 수 순으로 경계를 찾는다.
     */
    private static List<String> splitIntoUnits(String text, int limit) {
        List<String> units = new ArrayList<>();
        for (String line : text.split("\\n")) {
            String l = line.strip();
            if (l.isEmpty()) {
                continue;
            }
            if (l.length() <= limit) {
                units.add(l);
                continue;
            }
            for (String sentence : l.split("(?<=[.!?。]|다\\.)\\s+")) {
                String s = sentence.strip();
                while (s.length() > limit) {
                    units.add(s.substring(0, limit));
                    s = s.substring(limit).strip();
                }
                if (!s.isEmpty()) {
                    units.add(s);
                }
            }
        }
        return units;
    }

    /**
     * 조각을 순서대로 이어 붙여 {@code limit} 를 넘기 직전에 끊는다.
     */
    private static List<String> packPieces(List<String> units, int limit) {
        List<String> packed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.length() > 0 && current.length() + 1 + unit.length() > limit) {
                packed.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(unit);
        }
        if (current.length() > 0) {
            packed.add(current.toString());
        }
        return packed;
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(line);
    }

    private static String joinNonBlank(String a, String b) {
        StringBuilder sb = new StringBuilder();
        if (a != null && !a.isBlank()) {
            sb.append(a);
        }
        if (b != null && !b.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(b);
        }
        return sb.toString();
    }
}
