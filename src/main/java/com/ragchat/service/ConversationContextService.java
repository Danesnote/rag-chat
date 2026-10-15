package com.ragchat.service;

import io.micrometer.common.util.StringUtils;
import com.ragchat.config.ContextBudget;
import com.ragchat.dto.integration.llm.LlmMessage;
import com.ragchat.entity.ChatMessageEntity;
import com.ragchat.entity.ChatSummaryEntity;
import com.ragchat.repository.ChatMessageRepository;
import com.ragchat.repository.ChatSummaryRepository;
import com.ragchat.util.AnswerSections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 멀티턴 프롬프트의 "대화 메모리" 부분(이전 대화 요약 + 최근 원문)을 구성한다.
 *
 * <p>프롬프트 크기를 예산 안으로 묶기 위해, 오래된 대화는 요약 1건으로 대체하고
 * 최근 대화만 {@code history.retained.target-tokens} 예산 내에서 원문으로 싣는다.
 * 요약과 원문을 합친 크기는 {@code history.memory.max-tokens}를 넘지 않는다.</p>
 *
 * <p>원문 윈도잉은 <b>턴(사용자 질문 + 그에 딸린 답변) 단위</b>로 자른다. 메시지 단위로 자르면
 * 질문이 없는 답변부터 이력이 시작될 수 있는데, 모델이 맥락을 오해하고 일부 chat template은
 * user/assistant 교대를 요구하므로 렌더링 자체가 어긋난다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationContextService {

    private final ChatMessageRepository messageRepository;
    private final ChatSummaryRepository summaryRepository;
    private final ContextBudget budget;
    private final TokenizeService tokenizeService;

    /** 요약을 system 프롬프트에 덧붙일 때 사용할 머리말. 요약을 근거/출처로 쓰지 못하도록 못박는다. */
    private static final String SUMMARY_HEADER =
            "\n\n[이전 대화 요약]\n"
            + "아래는 같은 대화방에서 앞서 주고받은 내용의 요약이다. 대명사나 생략된 주제를 해석하는 등\n"
            + "맥락 파악에만 사용하고, 답변의 근거나 [출처]로는 절대 인용하지 마라.\n";

    /**
     * 한 대화방의 "대화 메모리".
     *
     * @param summaryBlock system 프롬프트 뒤에 덧붙일 요약 블록. 요약이 없으면 빈 문자열.
     *                     (요약을 별도 system 메시지로 보내면 chat template에 따라 무시되거나 오류가 나므로
     *                      system 메시지는 하나로 합친다.)
     * @param turns 최근 원문 턴 메시지들. 항상 {@code user} 메시지로 시작한다. 없으면 빈 목록.
     * @param estimatedTokens 요약 + 원문 턴의 토큰 수 합계
     */
    public record ConversationMemory(String summaryBlock, List<LlmMessage> turns, int estimatedTokens) {

        public static ConversationMemory empty() {
            return new ConversationMemory("", new ArrayList<>(), 0);
        }

        public boolean hasSummary() {
            return StringUtils.isNotBlank(summaryBlock);
        }
    }

    /**
     * 특정 대화방의 이력을 대화 메모리로 구성한다. (system 프롬프트와 현재 질문은 호출부에서 붙인다.)
     *
     * @param chatSeq 대화방 식별자
     * @return 요약 블록 + 최근 원문 턴
     */
    public ConversationMemory buildMemory(Long chatSeq) {
        // 1. 최신 요약 조회
        ChatSummaryEntity summary = summaryRepository
                .findTopByChatSeqOrderBySummaryVersionDesc(chatSeq)
                .orElse(null);
        long baseSeq = summary != null ? summary.getToMessageSeq() : 0L;

        String summaryBlock = "";
        int summaryTokens = 0;
        if (summary != null && StringUtils.isNotBlank(summary.getSummaryContent())) {
            summaryBlock = SUMMARY_HEADER + summary.getSummaryContent().trim() + "\n";
            summaryTokens = tokensOf(summary);
        }

        // 2. 요약 이후(미요약)의 최근 메시지들
        List<ChatMessageEntity> recent =
                messageRepository.findAllByChatSeq_IdAndIdGreaterThanOrderByIdAsc(chatSeq, baseSeq);

        // 3. 원문 예산 = retained 목표치. 단, 요약까지 합쳐 memory 상한을 넘지 않도록 줄인다.
        int retainedBudget = Math.min(
                budget.getRetainedTargetTokens(),
                Math.max(0, budget.getMemoryMaxTokens() - summaryTokens));

        // 4. 최근 원문을 턴 단위로 윈도잉 (최신 턴부터 담고, 초과하면 오래된 턴 컷)
        List<ChatMessageEntity> window = windowTurnsByBudget(recent, retainedBudget);

        // 5. 원문 턴 메시지 변환
        //    답변의 [출처]/[연관질문]은 화면·DB용 부가 영역이라 다음 턴 입력으로 되돌릴 필요가 없다.
        //    그대로 실으면 턴마다 예산을 잠식하고, 모델이 현재 [CONTEXT]에 없는 이전 출처를 베껴 쓴다.
        List<LlmMessage> turns = new ArrayList<>();
        int turnTokens = 0;
        int strippedTokens = 0;
        for (ChatMessageEntity message : window) {
            String content = message.getContent();
            if (message.getRole() == ChatMessageEntity.MessageRole.ASSISTANT) {
                String stripped = AnswerSections.strip(content);
                if (StringUtils.isNotBlank(stripped)) {
                    strippedTokens += tokensOf(message) - tokenizeService.estimateTokens(stripped);
                    content = stripped;
                }
                // 전부 잘려 나가면(부가 영역만 있던 답변) 원문을 그대로 둔다.
            }
            turns.add(new LlmMessage(toLlmRole(message.getRole()), content));
            turnTokens += tokenizeService.estimateTokens(content);
        }

        log.info("[Context] 방 {} - 요약 {}건({}토큰), 최근 원문 {}턴/{}메시지({}토큰, 출처·연관질문 {}토큰 제외), 원문예산 {}토큰",
                chatSeq, summary != null ? 1 : 0, summaryTokens,
                countUserTurns(window), turns.size(), turnTokens, strippedTokens, retainedBudget);

        return new ConversationMemory(summaryBlock, turns, summaryTokens + turnTokens);
    }

    /**
     * 오름차순 메시지를 턴 단위로 묶는다. 각 턴은 {@code USER} 메시지로 시작하고 그에 딸린 답변들을 포함한다.
     *
     * <p>맨 앞에 질문 없는 답변(고아 메시지)이 있으면 그것들만으로 된 블록이 선두에 온다.
     * 호출부에서 선두 블록이 {@code USER}로 시작하는지 확인해 걸러낼 수 있다.</p>
     *
     * @param ascending id 오름차순 메시지 목록
     * @return 턴 목록. 각 원소는 한 턴의 메시지 목록
     */
    public static List<List<ChatMessageEntity>> groupIntoTurns(List<ChatMessageEntity> ascending) {
        List<List<ChatMessageEntity>> turns = new ArrayList<>();
        List<ChatMessageEntity> current = null;
        for (ChatMessageEntity message : ascending) {
            if (current == null || message.getRole() == ChatMessageEntity.MessageRole.USER) {
                current = new ArrayList<>();
                turns.add(current);
            }
            current.add(message);
        }
        return turns;
    }

    /**
     * 선두 블록이 사용자 질문으로 시작하는지 여부.
     */
    public static boolean startsWithUser(List<ChatMessageEntity> turn) {
        return !turn.isEmpty() && turn.get(0).getRole() == ChatMessageEntity.MessageRole.USER;
    }

    /**
     * 턴 단위로 최신부터 예산 내로 담아 반환한다.
     * (최소 1턴은 유지하여 빈 이력이 되지 않도록 한다. 선두의 고아 답변 블록은 버린다.)
     */
    private List<ChatMessageEntity> windowTurnsByBudget(List<ChatMessageEntity> ascending, int budgetTokens) {
        List<List<ChatMessageEntity>> turns = groupIntoTurns(ascending);

        // 질문 없이 답변으로 시작하는 선두 블록은 맥락을 오해시키므로 제외한다.
        if (!turns.isEmpty() && !startsWithUser(turns.get(0))) {
            turns.remove(0);
        }

        List<List<ChatMessageEntity>> picked = new ArrayList<>();
        int total = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            List<ChatMessageEntity> turn = turns.get(i);
            int tokens = tokensOfTurn(turn);
            if (!picked.isEmpty() && total + tokens > budgetTokens) {
                break;
            }
            picked.add(turn);
            total += tokens;
        }
        Collections.reverse(picked); // 시간순으로 복원

        List<ChatMessageEntity> flattened = new ArrayList<>();
        for (List<ChatMessageEntity> turn : picked) {
            flattened.addAll(turn);
        }
        return flattened;
    }

    private int tokensOfTurn(List<ChatMessageEntity> turn) {
        int total = 0;
        for (ChatMessageEntity message : turn) {
            total += tokensOf(message);
        }
        return total;
    }

    private static long countUserTurns(List<ChatMessageEntity> messages) {
        return messages.stream()
                .filter(m -> m.getRole() == ChatMessageEntity.MessageRole.USER)
                .count();
    }

    /**
     * 저장된 토큰 수를 쓰되, 없거나 0이면(과거 데이터 또는 토큰 계산 실패분) 추정치로 대체한다.
     * 0을 그대로 합산하면 예산 초과가 발생할 수 없어 윈도잉이 무력화된다.
     */
    private int tokensOf(ChatMessageEntity message) {
        Integer stored = message.getTokenCount();
        if (stored != null && stored > 0) {
            return stored;
        }
        return tokenizeService.estimateTokens(message.getContent());
    }

    private int tokensOf(ChatSummaryEntity summary) {
        Integer stored = summary.getTokenCount();
        if (stored != null && stored > 0) {
            return stored;
        }
        return tokenizeService.estimateTokens(summary.getSummaryContent());
    }

    private static String toLlmRole(ChatMessageEntity.MessageRole role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
        };
    }
}
