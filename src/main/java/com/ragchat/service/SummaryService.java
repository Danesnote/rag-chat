package com.ragchat.service;

import io.micrometer.common.util.StringUtils;
import jakarta.annotation.PostConstruct;
import com.ragchat.config.ContextBudget;
import com.ragchat.dto.integration.llm.LlmChatRequest;
import com.ragchat.dto.integration.llm.LlmChatResponse;
import com.ragchat.dto.integration.llm.LlmChoice;
import com.ragchat.dto.integration.llm.LlmMessage;
import com.ragchat.entity.ChatMessageEntity;
import com.ragchat.entity.ChatSummaryEntity;
import com.ragchat.repository.ChatMessageRepository;
import com.ragchat.repository.ChatSummaryRepository;
import com.ragchat.util.HttpUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 오래된 대화를 요약하여 {@code demo_chat_summary}에 누적 저장하는 서비스.
 *
 * <p>답변이 끝난 뒤 비동기로 실행되어 사용자 응답을 지연시키지 않는다. 미요약 원문 토큰이
 * {@code history.summarize.trigger-tokens}를 넘으면, 최근 {@code history.retained.target-tokens}만
 * 원문으로 남기고 그보다 오래된 대화를 (기존 요약과 합쳐) 새 버전으로 압축한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryService {

    private final HttpUtils httpUtils;
    private final ChatMessageRepository messageRepository;
    private final ChatSummaryRepository summaryRepository;
    private final ContextBudget budget;
    private final TokenizeService tokenizeService;

    @Value("${rag.llm.url}")
    private String llmUrl;
    @Value("${rag.llm.model}")
    private String model;
    @Value("${rag.llm.api-key}")
    private String apiKey;

    /**
     * 요약 호출의 추론 강도.
     *
     * <p>비워 보내면 서버 기본값(gpt-oss 계열은 medium)이 적용되는데, 추론 토큰도
     * {@code max_tokens} 안에서 소비되므로 모델이 추론만 하다 예산을 다 쓰고
     * {@code content} 를 비운 채 끝날 수 있다({@code finish_reason=length}).
     * 요약 호출은 {@code summary.target-tokens}(2500) 처럼 예산이 넉넉하지 않아 특히 취약하다.
     */
    @Value("${summary.reasoning-effort:low}")
    private String reasoningEffort;

    @Value("classpath:prompts/summary-prompt.md")
    private Resource summaryPromptResource;
    private String summaryPrompt;

    @PostConstruct
    void init() {
        try {
            this.summaryPrompt = summaryPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("요약 프롬프트 로드 실패", e);
        }
    }

    /**
     * 필요 시(미요약 원문이 트리거 초과) 오래된 대화를 요약하여 저장한다.
     * 답변 완료 후 비동기로 호출된다. 실패해도 본 기능에 영향을 주지 않는다.
     *
     * @param chatSeq 대화방 식별자
     */
    @Async
    @Transactional
    public void maybeSummarizeAsync(Long chatSeq) {
        try {
            ChatSummaryEntity previous = summaryRepository
                    .findTopByChatSeqOrderBySummaryVersionDesc(chatSeq)
                    .orElse(null);
            long baseSeq = previous != null ? previous.getToMessageSeq() : 0L;

            List<ChatMessageEntity> recent =
                    messageRepository.findAllByChatSeq_IdAndIdGreaterThanOrderByIdAsc(chatSeq, baseSeq);
            int recentTokens = recent.stream().mapToInt(this::tokensOf).sum();

            // 아직 요약할 만큼 쌓이지 않음
            if (recentTokens <= budget.getSummarizeTriggerTokens()) {
                return;
            }

            // 최근 retained 예산만 원문으로 남기고, 그보다 오래된 것을 요약 대상으로
            List<ChatMessageEntity> toSummarize = olderBeyondRetained(recent, budget.getRetainedTargetTokens());
            if (toSummarize.isEmpty()) {
                return;
            }

            String input = buildSummarizationInput(previous, toSummarize);
            String summaryText = summarize(input, budget.getSummaryTargetTokens());
            if (StringUtils.isBlank(summaryText)) {
                log.warn("[Summary] 방 {} 요약 결과가 비어 저장하지 않음", chatSeq);
                return;
            }

            int summaryTokens = tokenizeService.countTokensSafe(summaryText);

            // 요약이 너무 커졌으면 재압축
            if (summaryTokens > budget.getSummaryMaxTokens()) {
                String compressed = summarize(summaryText, budget.getSummaryCompressionTargetTokens());
                if (StringUtils.isNotBlank(compressed)) {
                    summaryText = compressed;
                    summaryTokens = tokenizeService.countTokensSafe(summaryText);
                }
            }

            int newVersion = previous != null ? previous.getSummaryVersion() + 1 : 1;
            long fromSeq = previous != null ? previous.getFromMessageSeq() : toSummarize.get(0).getId();
            long toSeq = toSummarize.get(toSummarize.size() - 1).getId();

            summaryRepository.save(ChatSummaryEntity.builder()
                    .chatSeq(chatSeq)
                    .summaryVersion(newVersion)
                    .fromMessageSeq(fromSeq)
                    .toMessageSeq(toSeq)
                    .summaryContent(summaryText)
                    .tokenCount(summaryTokens)
                    .build());

            log.info("[Summary] 방 {} 요약 v{} 저장 (구간 {}~{}, {}토큰)", chatSeq, newVersion, fromSeq, toSeq, summaryTokens);

        } catch (Exception e) {
            log.warn("[Summary] 방 {} 요약 생성 실패(무시): {}", chatSeq, e.getMessage());
        }
    }

    /**
     * 오름차순 메시지 중, 최근 retained 예산에 포함되지 않는 "더 오래된" 부분을 반환한다.
     *
     * <p>경계는 <b>턴(사용자 질문 + 그에 딸린 답변) 단위</b>로 잡는다.
     * {@link ConversationContextService}의 원문 윈도잉과 같은 기준이어야, 요약으로 넘긴 구간과
     * 원문으로 남기는 구간이 한 턴을 반씩 쪼개지 않는다.</p>
     */
    private List<ChatMessageEntity> olderBeyondRetained(List<ChatMessageEntity> ascending, int retainedBudget) {
        List<List<ChatMessageEntity>> turns = ConversationContextService.groupIntoTurns(ascending);

        int total = 0;
        int keepFromTurn = turns.size(); // 이 턴부터 끝까지는 원문 유지
        for (int i = turns.size() - 1; i >= 0; i--) {
            int tokens = tokensOfTurn(turns.get(i));
            if (total + tokens > retainedBudget) {
                break;
            }
            total += tokens;
            keepFromTurn = i;
        }
        // 마지막 턴 하나가 예산보다 커도 원문으로는 남긴다. (원문 윈도잉이 최소 1턴을 유지하는 것과 같은 기준)
        if (keepFromTurn == turns.size() && !turns.isEmpty()) {
            keepFromTurn = turns.size() - 1;
        }

        List<ChatMessageEntity> older = new ArrayList<>();
        for (int i = 0; i < keepFromTurn; i++) {
            older.addAll(turns.get(i));
        }
        return older;
    }

    private int tokensOfTurn(List<ChatMessageEntity> turn) {
        int total = 0;
        for (ChatMessageEntity message : turn) {
            total += tokensOf(message);
        }
        return total;
    }

    private String buildSummarizationInput(ChatSummaryEntity previous, List<ChatMessageEntity> toSummarize) {
        StringBuilder sb = new StringBuilder();
        if (previous != null && StringUtils.isNotBlank(previous.getSummaryContent())) {
            sb.append("[기존 요약]\n").append(previous.getSummaryContent()).append("\n\n");
        }
        sb.append("[추가 대화]\n");
        for (ChatMessageEntity message : toSummarize) {
            String speaker = message.getRole() == ChatMessageEntity.MessageRole.USER ? "사용자" : "상담원";
            sb.append(speaker).append(": ").append(message.getContent()).append("\n");
        }
        return sb.toString();
    }

    /**
     * vLLM 비스트리밍 호출로 요약을 생성한다.
     */
    private String summarize(String input, int maxTokens) {
        LlmChatRequest request = LlmChatRequest.builder()
                .model(model)
                .messages(List.of(
                        new LlmMessage("system", summaryPrompt),
                        new LlmMessage("user", input)
                ))
                .maxTokens(maxTokens)
                .temperature(0.2)
                // 생략하면 서버 기본 추론 강도(medium)가 적용되어 추론이 maxTokens 를 잠식한다.
                // 요약은 추론이 거의 필요 없는 작업이므로 낮게 고정한다.
                .reasoningEffort(StringUtils.isNotBlank(reasoningEffort) ? reasoningEffort : null)
                .stream(false)
                .build();

        LlmChatResponse response = httpUtils.post(
                llmUrl,
                Map.of("Authorization", "Bearer " + apiKey),
                request,
                LlmChatResponse.class
        );

        if (response != null && response.choices() != null && !response.choices().isEmpty()) {
            LlmChoice choice = response.choices().get(0);
            LlmMessage message = choice.message();
            if (message != null) {
                if (StringUtils.isBlank(message.getContent())) {
                    // 추론이 예산을 다 쓴 경우가 대부분이다. finish_reason=length 면 reasoning-effort 를
                    // 낮추거나 summary.target-tokens 를 올려야 한다.
                    int reasoningLength = message.getReasoningContent() != null
                            ? message.getReasoningContent().length() : 0;
                    log.warn("[Summary][LLM] 본문이 비었습니다. finish_reason={}, 추론 {}자, "
                                    + "max_tokens={}, reasoning_effort={}",
                            choice.finishReason(), reasoningLength, maxTokens, reasoningEffort);
                } else {
                    log.debug("[Summary][LLM] 응답 원문: {}", message.getContent());
                }
                return message.getContent();
            }
        }
        log.warn("[Summary][LLM] 응답에 choices/message가 없습니다.");
        return null;
    }

    /**
     * 저장된 토큰 수를 쓰되, 없거나 0이면(과거 데이터 또는 토큰 계산 실패분) 추정치로 대체한다.
     * 0을 그대로 합산하면 요약 트리거가 영구히 발동하지 않는다.
     */
    private int tokensOf(ChatMessageEntity message) {
        Integer stored = message.getTokenCount();
        if (stored != null && stored > 0) {
            return stored;
        }
        return tokenizeService.estimateTokens(message.getContent());
    }
}
