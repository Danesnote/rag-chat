package com.ragchat.service;

import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PostConstruct;
import com.ragchat.dto.integration.llm.LlmChatRequest;
import com.ragchat.dto.integration.llm.LlmChatResponse;
import com.ragchat.dto.integration.llm.LlmChoice;
import com.ragchat.dto.integration.llm.LlmMessage;
import com.ragchat.util.AnswerSections;
import com.ragchat.util.HttpUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 멀티턴 후속 질문을 "그 자체로 완결된 검색 질의문"으로 재작성하는 서비스.
 *
 * <p>검색은 이전 대화를 모르므로, "그럼 예외는?" 같은 대명사·생략형 질문을 원문 그대로
 * 검색하면 엉뚱한 문서가 오거나 결과가 비게 된다. 그러면 LLM은 이력을 읽어 의도는 이해하지만
 * 근거가 틀려서 답을 못 하는 형태로 실패한다. 이 서비스는 검색 <b>직전에만</b> 쓰이는
 * 질의문을 만들며, LLM에 전달하는 사용자 질문 원문은 바꾸지 않는다.</p>
 *
 * <p>첫 턴(이전 대화 없음)에는 호출하지 않으므로 추가 지연이 없다. 재작성 호출이 실패하거나
 * 결과가 의심스러우면 원문 질문으로 그대로 폴백한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private final HttpUtils httpUtils;

    @Value("${rag.llm.url}")
    private String llmUrl;
    @Value("${rag.llm.model}")
    private String model;
    @Value("${rag.llm.api-key}")
    private String apiKey;

    /** 재작성 사용 여부. 문제가 생기면 false로 끄면 즉시 원문 검색으로 돌아간다. */
    @Value("${rag.query-rewrite.enabled:true}")
    private boolean enabled;
    @Value("${rag.query-rewrite.first-turn:true}")
    private boolean rewriteFirstTurn;
    /**
     * 재작성 응답 최대 토큰. 결과는 질의문 한 문장뿐이지만, 추론형 모델이 reasoning에 토큰을 먼저 쓰고
     * content를 비운 채 끝내지 않도록 여유를 둔다. (너무 작게 잡으면 매번 원문 폴백이 된다)
     */
    @Value("${rag.query-rewrite.max-tokens:512}")
    private int maxTokens;
    /**
     * 재작성 호출의 추론 강도.
     *
     * <p><b>반드시 낮게 지정한다.</b> 값을 비워 보내면 서버 기본값(gpt-oss 계열은 medium)이 적용되는데,
     * 추론 토큰도 {@code max_tokens} 안에서 소비되므로 모델이 추론만 하다 예산을 다 쓰고
     * {@code content} 를 비운 채 끝난다({@code finish_reason=length}). 그러면 매번 원문 폴백이 되어
     * 멀티턴 검색이 조용히 실패한다. 재작성은 한 문장만 만들면 되므로 추론이 거의 필요 없다.
     */
    @Value("${rag.query-rewrite.reasoning-effort:low}")
    private String reasoningEffort;
    /** 재작성에 참고할 최근 턴 수(사용자 질문 기준). */
    @Value("${rag.query-rewrite.max-turns:3}")
    private int maxTurns;
    /** 재작성 결과가 이 길이를 넘으면 질의문이 아니라고 보고 원문으로 폴백한다. */
    @Value("${rag.query-rewrite.max-query-chars:200}")
    private int maxQueryChars;
    /** 참고용 이전 답변을 이 길이로 잘라 넣는다. (프롬프트 비대화 방지) */
    @Value("${rag.query-rewrite.max-answer-chars:400}")
    private int maxAnswerChars;

    @Value("classpath:prompts/query-rewrite-prompt.md")
    private Resource rewritePromptResource;
    private String rewritePrompt;

    @PostConstruct
    void init() {
        try {
            this.rewritePrompt = rewritePromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("질의 재작성 프롬프트 로드 실패", e);
        }
    }

    /**
     * 이전 대화를 반영해 검색용 질의문을 만든다.
     *
     * @param priorTurns 이전 대화 원문 턴들(현재 질문은 포함하지 않는다). 비어 있으면 재작성하지 않는다.
     * @param question 현재 사용자 질문 원문
     * @return 검색에 사용할 질의문. 재작성이 불필요·불가하면 {@code question} 그대로
     */
    public String rewriteForSearch(List<LlmMessage> priorTurns, String question) {
        if (!enabled || priorTurns == null || priorTurns.isEmpty() || StringUtils.isBlank(question)) {
            return question;
        }
        boolean firstTurn = (priorTurns == null || priorTurns.isEmpty());
        if(firstTurn && !rewriteFirstTurn){
            return question;
        }
        try {
            String input = buildInput(priorTurns, question);

            LlmChatRequest request = LlmChatRequest.builder()
                    .model(model)
                    .messages(List.of(
                            new LlmMessage("system", rewritePrompt),
                            new LlmMessage("user", input)
                    ))
                    .maxTokens(maxTokens)
                    .temperature(0.0)   // 검색어는 재현성이 중요하므로 무작위성 제거
                    // 생략하면 서버 기본 추론 강도가 적용되어 max_tokens 를 추론으로 다 써버린다.
                    .reasoningEffort(StringUtils.isNotBlank(reasoningEffort) ? reasoningEffort : null)
                    .stream(false)
                    .build();

            LlmChatResponse response = httpUtils.post(
                    llmUrl,
                    Map.of("Authorization", "Bearer " + apiKey),
                    request,
                    LlmChatResponse.class
            );

            String rewritten = extractContent(response);
            logResponseDetail(response, rewritten);
            String sanitized = sanitize(rewritten);

            if (sanitized == null) {
                log.info("[QueryRewrite] 결과가 유효하지 않아 원문으로 검색: {}", question);
                return question;
            }

            log.info("[QueryRewrite] '{}' -> '{}'", question, sanitized);
            return sanitized;

        } catch (Exception e) {
            log.warn("[QueryRewrite] 재작성 실패, 원문으로 검색: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 재작성 입력을 만든다. 이전 답변에서 [출처]/[연관질문] 영역은 검색어 판단에 방해가 되므로 잘라낸다.
     */
    private String buildInput(List<LlmMessage> priorTurns, String question) {
        List<LlmMessage> recent = takeRecentTurns(priorTurns, maxTurns);

        StringBuilder sb = new StringBuilder("[이전 대화]\n");
        for (LlmMessage message : recent) {
            if (StringUtils.isBlank(message.getContent())) {
                continue;
            }
            boolean isUser = "user".equals(message.getRole());
            String body = isUser
                    ? message.getContent().trim()
                    : truncate(AnswerSections.strip(message.getContent()), maxAnswerChars);
            if (StringUtils.isBlank(body)) {
                continue;
            }
            sb.append(isUser ? "사용자: " : "상담원: ").append(body).append("\n");
        }
        sb.append("\n[현재 질문]\n").append(question.trim()).append("\n");
        return sb.toString();
    }

    /**
     * 최근 N턴(사용자 질문 기준)만 남긴다. 턴 경계를 지켜 사용자 질문부터 시작하도록 자른다.
     */
    private static List<LlmMessage> takeRecentTurns(List<LlmMessage> priorTurns, int turnLimit) {
        if (turnLimit <= 0) {
            return priorTurns;
        }
        int userSeen = 0;
        int startIndex = 0;
        for (int i = priorTurns.size() - 1; i >= 0; i--) {
            if ("user".equals(priorTurns.get(i).getRole())) {
                userSeen++;
                startIndex = i;
                if (userSeen == turnLimit) {
                    break;
                }
            }
        }
        return new ArrayList<>(priorTurns.subList(startIndex, priorTurns.size()));
    }


    private static String truncate(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    /**
     * 재작성 응답을 진단 가능한 형태로 남긴다.
     *
     * <p>{@code content} 가 비어 있을 때 원인을 바로 가릴 수 있도록
     * {@code finish_reason} 과 추론 토큰 소비 여부를 함께 남긴다.
     * <ul>
     *   <li>{@code finish_reason=length} + 추론 {@code N자} → 추론이 max_tokens 를 다 씀.
     *       {@code reasoning-effort} 를 낮추거나 {@code max-tokens} 를 올린다.</li>
     *   <li>{@code finish_reason=stop} + 내용 없음 → 프롬프트가 빈 출력을 유도한 경우</li>
     * </ul>
     *
     * @param response LLM 응답
     * @param content 응답에서 추출한 본문(없으면 null)
     */
    private void logResponseDetail(LlmChatResponse response, String content) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            log.warn("[QueryRewrite][LLM] 응답에 choices 가 없습니다.");
            return;
        }
        LlmChoice choice = response.choices().get(0);
        LlmMessage message = choice.message();
        int reasoningLength = (message != null && message.getReasoningContent() != null)
                ? message.getReasoningContent().length() : 0;

        if (StringUtils.isBlank(content)) {
            log.warn("[QueryRewrite][LLM] 본문이 비어 원문으로 폴백합니다. "
                            + "finish_reason={}, 추론 {}자, max_tokens={}, reasoning_effort={}",
                    choice.finishReason(), reasoningLength, maxTokens, reasoningEffort);
        } else {
            log.info("[QueryRewrite][LLM] 응답 본문: {} (finish_reason={}, 추론 {}자)",
                    content, choice.finishReason(), reasoningLength);
        }
    }

    private static String extractContent(LlmChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return null;
        }
        LlmMessage message = response.choices().get(0).message();
        return message != null ? message.getContent() : null;
    }

    /**
     * 재작성 결과를 검증·정리한다. 질의문으로 보기 어려우면 {@code null}을 반환해 폴백하게 한다.
     */
    private String sanitize(String rewritten) {
        if (StringUtils.isBlank(rewritten)) {
            return null;
        }
        // 모델이 여러 줄로 답한 경우 내용이 있는 첫 줄만 사용한다.
        String first = null;
        for (String line : rewritten.strip().split("\\R")) {
            if (StringUtils.isNotBlank(line)) {
                first = line.strip();
                break;
            }
        }
        if (first == null) {
            return null;
        }
        // 프롬프트를 되풀이한 경우(라벨 그대로 출력) 폴백
        if (first.startsWith("[") || first.contains("[현재 질문]") || first.contains("[이전 대화]")) {
            return null;
        }
        // 감싸인 따옴표 제거
        first = first.replaceAll("^[\"'“”‘’]+|[\"'“”‘’]+$", "").strip();
        if (StringUtils.isBlank(first) || first.length() > maxQueryChars) {
            return null;
        }
        return first;
    }
}
