package com.ragchat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.common.util.StringUtils;
import com.ragchat.config.ContextBudget;
import com.ragchat.dto.integration.llm.LlmChatRequest;
import com.ragchat.dto.integration.llm.LlmChatResponse;
import com.ragchat.dto.integration.llm.LlmChoice;
import com.ragchat.dto.integration.llm.LlmMessage;
import com.ragchat.dto.integration.llm.LlmStreamOptions;
import com.ragchat.retrieval.DocumentChunk;
import com.ragchat.prompt.SystemPromptProvider;
import com.ragchat.service.ConversationContextService.ConversationMemory;
import com.ragchat.util.AnswerSections;
import com.ragchat.util.HttpUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ObjectMapper objectMapper;    //JSON 파싱용

    private final HttpUtils httpUtils;

    //가상 스레드로 내용 변경:: 처리량 효율 및 메모리 절약 측면에서 이점.
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    //검색 방식(모드1/2)에 따라 문서를 확보하는 서비스
    private final RetrievalService retrievalService;

    //대화내역 저장 수행
    private final HistoryService historyService;

    //시스템 프롬프트 제공(외부 파일 로드, 변경 시 자동 반영)
    private final SystemPromptProvider systemPromptProvider;

    //오래된 대화 비동기 요약(답변 후 실행)
    private final SummaryService summaryService;

    //멀티턴 후속 질문을 검색용 완결 질의문으로 재작성
    private final QueryRewriteService queryRewriteService;

    //프롬프트 토큰 예산
    private final ContextBudget budget;

    //예산 계산용 토큰 추정(요청 경로에서 여러 번 쓰므로 /tokenize 왕복 없이 추정치 사용)
    private final TokenizeService tokenizeService;

    /**
     * 이전 대화가 하나도 없는 턴에만 system 프롬프트 뒤에 덧붙이는 지시.
     *
     * <p>이력이 없으면 [유형 판정] 0단계(질문 복원)를 수행할 수 없다. 그럼에도 검색은 매 턴
     * 새로 수행되므로, '예외는?' 같은 질문에도 그럴듯한 문서가 [CONTEXT]에 담긴다. 이때 모델이
     * 검색 결과만 보고 생략된 주제를 추측해 답하면, 사용자는 이전 대화가 이어진 것으로 오해한다.
     * 그래서 이 경우에는 답변 대신 완결된 질문을 요청하게 한다.</p>
     */
    private static final String NO_HISTORY_GUIDE = """

            [대화 이력 없음]
            - 이번 턴에는 이전 대화가 하나도 없다. 따라서 [유형 판정]의 0단계(질문 복원)를 수행할 수 없다.
            - 현재 질문이 이전 대화를 전제로 하는 형태이면, [CONTEXT]에 그럴듯한 문서가 있더라도 무조건 유형2로 판정하라.
              해당하는 형태는 다음과 같다.
              + 지시어로 대상을 가리키는 경우: '그것', '그거', '이거', '해당 항목', '위 문서', '방금 그 내용'
              + 이전 발화를 가리키는 표현이 있는 경우: '아까', '방금', '앞서', '이전에', '말한', '설명한', '다시'
              + 주제가 생략된 후속 질문: '예외는?', '2급은요?', '왜?', '기한은?', '그럼 반대로는?'
            - 검색 결과로 생략된 주제를 추측해 채우는 것을 금지한다. 이전 대화가 있었던 것처럼 답하지 마라.
            - 이 경우의 출력은 아래 한 문장뿐이며, 출력 후 즉시 종료하라. [출처]와 [연관질문]도 작성하지 마라.
              '이전 대화 내용이 없어 질문의 대상을 알 수 없습니다. 궁금하신 문서나 항목을 포함해 완결된 문장으로 질문해 주세요.'
            """;

    /**
     * chat template이 메시지마다 추가하는 역할/구분 토큰의 대략치.
     * 예산 계산에서 메시지 수만큼 더해 과소 추정을 막는다.
     */
    private static final int PER_MESSAGE_OVERHEAD_TOKENS = 8;

    //properties로 빼서 사용
    @Value("${rag.llm.url}")
    private String LLM_URL;
    @Value("${rag.llm.model}")
    private String MODEL_NAME;
    @Value("${rag.llm.api-key}")
    private String API_KEY;
    // 스트리밍 응답에 토큰 사용량(usage)을 포함시킬지 여부. 서버가 stream_options를 거부하면 false로 두면 된다.
    @Value("${rag.llm.include-usage:true}")
    private boolean includeUsage;
    // 답변 생성 최대 토큰. 컨텍스트 한도(131072) 내로 제한한다. (request.generation.medium 기준)
    @Value("${request.generation.medium.max-tokens:16384}")
    private int generationMaxTokens;
    // 검색 방식 기본값(1: 하이브리드, 2: 하이브리드 + 인접 청크 확장). 요청에 retrievalMode가 없을 때 사용
    @Value("${rag.retrieval.mode:2}")
    private int defaultRetrievalMode;
    // [CONTEXT]에 최소한 확보해야 할 토큰. 이보다 여유가 없으면 오래된 대화 턴부터 버린다.
    @Value("${context.rag.min-tokens:8000}")
    private int minContextTokens;
    /**
     * 화면(답변 탭 카드 / 검색결과 탭)에 표시할 <b>채택 출처</b> 최대 건수.
     *
     * <p>0 이하면 답변이 인용한 출처를 전부 표시한다(기본값). 화면에는 [CONTEXT] 전체가 아니라
     * 답변 근거로 실제 인용된 문서만 나가므로, 보통 제한을 걸 필요가 없다.
     * 값을 주면 인용 순서(=[CONTEXT] 문서 번호 순) 기준 상위 N건만 남긴다.
     */
    @Value("${rag.retrieval.source-display-limit:0}")
    private int sourceDisplayLimit;

    /**
     * [CONTEXT] 블록 조립 결과.
     *
     * @param text LLM에 전달할 "[CONTEXT]..." 문자열
     * @param usedDocuments 실제로 담긴 문서 수 (예산 때문에 뒤쪽 문서가 빠질 수 있다)
     * @param estimatedTokens 추정 토큰 수
     */
    private record ContextBlock(String text, int usedDocuments, int estimatedTokens) {
    }

    /*
    * 비동기 스트리밍(SSE) 구현(한글자씩 쏘기)
    *
    * @param chatSeq 대화방 식별자
    * @param memory 이전 대화 메모리(요약 블록 + 최근 원문 턴). 현재 질문은 포함하지 않는다.
    * @param question 현재 사용자 질문 원문
    * */
    public void streamAnswer(Long chatSeq, ConversationMemory memory, String question,
                             SseEmitter emitter, String selectedModel, Integer retrievalMode) {
        // 별도 스레드에서 실행 (메인 스레드 차단 방지)
        executorService.execute(() -> {
            StringBuilder fullAnswer = new StringBuilder();
            int completionTokens = 0;   // include_usage로 받은 답변 토큰 수 (0이면 저장 단계에서 tokenize로 폴백)

            try {
                // ------------------------------------------------------------------
                // Step 1: 검색용 질의어 확정
                //   검색은 이전 대화를 모른다. "그럼 예외는?" 같은 대명사·생략형 후속 질문은
                //   이전 턴을 반영해 완결된 질의문으로 재작성한 뒤 검색한다.
                //   LLM에 전달하는 질문은 재작성문이 아니라 사용자 원문을 그대로 쓴다.
                // ------------------------------------------------------------------
                String searchQuery = queryRewriteService.rewriteForSearch(memory.turns(), question);

                // ------------------------------------------------------------------
                // Step 2-3: 하이브리드 검색(벡터 + 키워드 → RRF) 후 리랭킹
                //   모드2이면 리랭킹된 청크의 앞/뒤 청크까지 확장한다. (RetrievalService)
                // ------------------------------------------------------------------
                int mode = (retrievalMode != null) ? retrievalMode : defaultRetrievalMode;
                List<RetrievalService.RetrievalItem> retrieved = retrievalService.retrieve(searchQuery, mode);

                // 본문이 없는 항목은 [CONTEXT]에 담을 수 없으므로 먼저 걸러낸다.
                // (RetrievalService가 이미 걸러 주지만, 문서 순서와 화면 출처 순서를 1:1로 맞추는
                //  전제가 깨지지 않도록 여기서 한 번 더 확인한다.)
                List<RetrievalService.RetrievalItem> items = new ArrayList<>();
                for (RetrievalService.RetrievalItem item : retrieved) {
                    if (item.chunk() != null && item.chunk().hasContent()) {
                        items.add(item);
                    }
                }

                if (items.isEmpty()) {
                    // 빈 결과의 원인을 한 줄로 구분해 남긴다.
                    //  - retrieved=0  → 검색 자체가 0건 (적재된 문서가 없거나 질의 문제)
                    //  - retrieved>0  → 검색은 됐지만 본문이 비어 컨텍스트로 못 씀
                    long withDocId = retrieved.stream()
                            .filter(it -> StringUtils.isNotEmpty(it.chunkId()))
                            .count();
                    log.warn("[Retrieval] 컨텍스트로 쓸 문서 0건. mode={}, retrieved={}건(ID有 {}건), 검색질의='{}'. "
                                    + "retrieved=0이면 검색 자체가 0건(문서 미적재·질의 문제), retrieved>0이면 본문이 비어 있는 것이다.",
                            mode, retrieved.size(), withDocId, searchQuery);
                    emitter.completeWithError(new RuntimeException("검색 결과가 없습니다."));
                    return;
                }

                // ------------------------------------------------------------------
                // Step 4: 입력 토큰 예산 배분
                //   우선순위: system 프롬프트 + 현재 질문 > [CONTEXT] > 이전 대화 턴
                //   [CONTEXT] 여유가 최소치에 못 미치면 오래된 턴부터 버려 확보한다.
                // ------------------------------------------------------------------
                String systemMessage = systemPromptProvider.get() + memory.summaryBlock();

                // 이전 대화가 하나도 없으면(새 대화방의 첫 질문 등) 후속형 질문의 주제를 복원할 방법이 없다.
                // 이 상태에서 '예외는?', '아까 말한 거' 같은 질문에 검색 결과만 보고 답하면
                // 사용자는 이전 대화를 기억하는 것으로 오해한다. 그래서 되묻도록 지시를 덧붙인다.
                if (memory.turns().isEmpty() && !memory.hasSummary()) {
                    systemMessage += NO_HISTORY_GUIDE;
                    log.info("[Context] 이전 대화 없음 - 후속형 질문 되묻기 지시를 system 프롬프트에 추가");
                }

                String questionBlock = "[USER_QUESTION]" + question;

                List<LlmMessage> turns = new ArrayList<>(memory.turns());
                int systemTokens = tokenizeService.estimateTokens(systemMessage);
                int questionTokens = tokenizeService.estimateTokens(questionBlock);

                int contextCap = contextCapacity(systemTokens, questionTokens, turns);
                while (contextCap < minContextTokens && !turns.isEmpty()) {
                    dropOldestTurn(turns);
                    contextCap = contextCapacity(systemTokens, questionTokens, turns);
                }
                if (turns.size() < memory.turns().size()) {
                    log.info("[Budget] 입력 예산 확보를 위해 이전 대화 {}메시지 제외 (남은 {}메시지, CONTEXT 여유 {}토큰)",
                            memory.turns().size() - turns.size(), turns.size(), contextCap);
                }
                if (contextCap < minContextTokens) {
                    log.warn("[Budget] 이력을 모두 제외해도 CONTEXT 여유가 {}토큰뿐입니다. system 프롬프트 크기를 점검하세요.", contextCap);
                }

                // LLM 컨텍스트: 검색 결과와 인접 청크를 동등한 근거 후보로 모두 싣는다.
                ContextBlock context = buildContext(items, Math.max(contextCap, 0));

                log.info("[Budget] 입력 예산 {}토큰 = system {} + 이력 {} + CONTEXT {}({}/{}건) + 질문 {}",
                        budget.inputBudget(), systemTokens, estimateTokens(turns),
                        context.estimatedTokens(), context.usedDocuments(), items.size(), questionTokens);

                // ------------------------------------------------------------------
                // Step 5: 출처 후보 목록 구성 ([CONTEXT]에 실제로 담긴 문서 전체)
                //   여기서는 후보를 모두 만들어 둔다. 화면과 DB에는 답변이 끝난 뒤
                //   실제로 인용된 것만 걸러서 내보낸다. (Step 8)
                //   목록의 인덱스+1 이 [CONTEXT]의 [문서N] 번호와 1:1로 대응해야 하므로
                //   이 단계에서는 절대 건너뛰거나 정렬을 바꾸지 않는다.
                // ------------------------------------------------------------------
                List<Map<String, Object>> sourceCandidates = new ArrayList<>();

                for (int i = 0; i < context.usedDocuments(); i++) {
                    RetrievalService.RetrievalItem item = items.get(i);
                    DocumentChunk fields = item.chunk();
                    Map<String, Object> sourceData = new HashMap<>();

                    // 프론트엔드 JS에서 src.id, src.title, src.content 로 받기로 한 규격에 맞춤
                    sourceData.put("id", fields.chunkId());

                    // [CONTEXT]의 문서 번호. 답변의 '- [문서N] ...' 표기와 화면 카드 번호를 맞추는 데 쓴다.
                    sourceData.put("docNo", i + 1);

                    // 제목
                    sourceData.put("title", fields.title() != null ? fields.title() : "제목 없음");

                    // 본문 (LLM이 읽은 것과 화면에 보이는 것이 동일해야 검증이 가능하다)
                    sourceData.put("content", fields.content());

                    // 확장 메타데이터 (화면 출처 표시용)
                    sourceData.put("version", fields.version());
                    sourceData.put("effectiveDate", fields.effectiveDate());

                    // 검색 결과인지 인접 확장인지 (화면 배지용)
                    sourceData.put("origin", item.origin().name());
                    sourceData.put("anchorId", item.anchorChunkId());

                    // 초기 상태는 채택 여부를 모르므로 무조건 false
                    sourceData.put("isUsed", false);
                    sourceCandidates.add(sourceData);
                }

                // 출처는 답변이 끝나야 채택 여부를 알 수 있으므로, 이 시점에는 전송하지 않는다.
                // (프론트는 used_sources 를 받을 때까지 출처 로딩 표시를 유지한다)
                String searchContentJson = null;

                // ------------------------------------------------------------------
                // Step 6: LLM 요청 생성
                // -----------------------------------------------------------------

                // 1. 호출 대상 LLM (OpenAI 호환 Chat Completions)
                String targetUrl = LLM_URL;
                String targetModel = MODEL_NAME;
                String targetApiKey = API_KEY;

                // vLLM Chat Completions API는 시스템 프롬프트도 messages로 전달한다.
                // system 메시지는 반드시 하나로 합친다. 요약을 두 번째 system 메시지로 보내면
                // chat template에 따라 무시되거나 렌더링에서 오류가 난다.
                List<LlmMessage> requestMessages = new ArrayList<>();
                requestMessages.add(new LlmMessage("system", systemMessage));
                requestMessages.addAll(turns);
                requestMessages.add(new LlmMessage("user", context.text() + questionBlock));

                // 2. 요청 DTO 생성 (stream: true)
                LlmChatRequest requestPayload = LlmChatRequest.builder()
                        .model(targetModel)
                        .messages(requestMessages)
                        .maxTokens(generationMaxTokens)
                        .temperature(0.1)       //창의성(최대한 검색결과에만 근거하게 최소값으로 세팅)
                        .topP(0.9)
                        .n(1)
                        .presencePenalty(0.0)
                        .frequencyPenalty(0.0)
                        .reasoningEffort("medium")
                        .stream(true)
                        // 답변 토큰 수를 usage로 무료 확보 (서버 미지원 시 include-usage=false로 끄면 tokenize로 폴백)
                        .streamOptions(includeUsage ? LlmStreamOptions.builder().includeUsage(true).build() : null)
                        // stop, user는 의도적으로 설정하지 않는다.
                        //  - stop("\n\n" 등): RAG 답변이 여러 문단으로 나뉘므로 중간에 잘릴 위험이 있어 미설정
                        //  - user: 사용자 식별 체계(SSO) 도입 후 연결 예정
                        .build();

                // LLM에 실제로 전송되는 프롬프트(전체 요청) 로깅
                logOutbound(targetUrl, requestPayload);

                //3. 공통 HttpClient 및 요청 생성
                HttpResponse<InputStream> response = httpUtils.postStream(
                        targetUrl,
                        Map.of("Authorization", "Bearer " + targetApiKey),
                        requestPayload
                );

                // ------------------------------------------------------------------
                // Step 7: 스트리밍 응답 처리
                // -----------------------------------------------------------------
                // [디버깅] 응답 상태 코드 확인 (200이 아니면 에러 로그 출력)
                int statusCode = response.statusCode();
                if (statusCode != 200) {
                    // 에러 본문을 읽어서 로그에 찍어봄
                    try (BufferedReader errReader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                        errReader.lines().forEach(line -> log.error("[LLM] Error Body: {}", line));
                    }
                    emitter.completeWithError(new RuntimeException("LLM Server Error: " + statusCode));
                    return;
                }

                //4. 한 줄씩 읽기
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // SSE 데이터 형식: "data: {JSON}"
                        if (line.startsWith("data:") && !line.contains("[DONE]")) {
                            String jsonPart = line.substring(5).trim(); // "data:" 제거

                            if (jsonPart.isEmpty()) continue;

                            // LLM이 내려준 원본 청크. 답변이 이상할 때 원문을 확인하는 용도이며
                            // 양이 많아 DEBUG 레벨에서만 출력한다.
                            log.debug("[LLM] 응답 청크: {}", jsonPart);

                            try {
                                LlmChatResponse chunk = objectMapper.readValue(jsonPart, LlmChatResponse.class);

                                // include_usage 활성화 시 마지막 청크에 usage가 담겨 온다 → 답변 토큰 수 확보
                                if (chunk.usage() != null && chunk.usage().completionTokens() > 0) {
                                    completionTokens = chunk.usage().completionTokens();
                                }

                                if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                                    LlmChoice choice = chunk.choices().get(0);
                                    String content = null;
                                    if (choice.delta() != null) {
                                        content = choice.delta().getContent(); // 스트리밍일 때
                                    } else if (choice.message() != null) {
                                        content = choice.message().getContent(); // 일반일 때
                                    }

                                    if (content != null && !content.isEmpty()) {
                                        // -----------------------------------------------------------
                                        // [수정] 전송 실패(연결 끊김) 시 우아하게 처리하기
                                        // -----------------------------------------------------------
                                        try {
                                            // 1. Frontend로 전송
                                            Map<String, String> data = new HashMap<>();
                                            data.put("content", content);
                                            emitter.send(data);

                                            // 2. 전송 성공 시에만 답변 누적
                                            fullAnswer.append(content);

                                        } catch (IOException | IllegalStateException e) {
                                            // 사용자가 브라우저를 닫거나 새로고침 함 -> 더 이상 LLM 읽을 필요 없음
                                            log.warn("클라이언트 연결이 종료되어 스트리밍을 중단합니다.");

                                            // ★ 중요: 스트리밍 루프(while)를 탈출하여 리소스를 아낌
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("JSON 파싱 에러 (무시하고 계속 진행): {}", line);
                            }
                        }
                    }
                }

                // ------------------------------------------------------------------
                // Step 8: 완료 후 DB 저장
                // -----------------------------------------------------------

                // 5. [중요] 답변이 정상적으로 만들어졌을 때만 히스토리에 저장
                String rawAnswer = fullAnswer.toString();

                // LLM이 최종적으로 만들어낸 답변 전문을 로그로 남긴다. (정규화 전 원문)
                logInbound(rawAnswer, completionTokens);

                // 모델이 [문서N]을 [document1] 처럼 영어로 바꿔 쓰는 경우가 있어 저장 전에 한글 표기로 통일한다.
                // DB·다음 턴 이력에 영어 표기가 남으면 모델이 그 표기를 따라 쓰게 되므로 여기서 정리한다.
                String finalAnswer = AnswerSections.normalizeCitationLabels(rawAnswer);
                if (!finalAnswer.equals(rawAnswer)) {
                    log.warn("[Sources] 답변의 문서 번호 라벨이 영어 등 비표준 표기여서 [문서N]으로 정규화했습니다.");
                }

                if (!finalAnswer.trim().isEmpty()) {
                    // 1. 답변이 인용한 [문서N] 을 찾아 채택 표시
                    markUsedSources(finalAnswer, sourceCandidates);

                    // 2. 채택된 출처만 남긴다. 검색 결과든 인접 청크든 구분 없이,
                    //    실제로 답변 근거가 된 것만 화면과 DB에 남는다.
                    List<Map<String, Object>> usedSources = new ArrayList<>();
                    for (Map<String, Object> source : sourceCandidates) {
                        if (Boolean.TRUE.equals(source.get("isUsed"))) {
                            usedSources.add(source);
                        }
                    }
                    if (sourceDisplayLimit > 0 && usedSources.size() > sourceDisplayLimit) {
                        log.info("[Sources] 채택 출처 {}건 중 상위 {}건만 표시합니다.", usedSources.size(), sourceDisplayLimit);
                        usedSources = new ArrayList<>(usedSources.subList(0, sourceDisplayLimit));
                    }
                    log.info("[Sources] 후보 {}건 중 채택 {}건을 노출합니다. 채택 문서번호={}",
                            sourceCandidates.size(), usedSources.size(),
                            usedSources.stream().map(s -> s.get("docNo")).toList());

                    // 3. 채택 출처를 프론트로 전송 (이벤트명: used_sources)
                    try {
                        emitter.send(SseEmitter.event()
                                .name("used_sources")
                                .data(usedSources));
                    } catch (IOException e) {
                        log.warn("최종 출처 프론트엔드 전송 실패", e);
                    }

                    // 4. DB 저장용 JSON 직렬화 (히스토리를 다시 열었을 때도 같은 출처가 보인다)
                    try {
                        searchContentJson = objectMapper.writeValueAsString(usedSources);
                    } catch (Exception e) {
                        log.error("출처 데이터 JSON 직렬화 실패", e);
                    }

                    // 5. [핵심] DB에 비동기 저장 (chatSeq 사용). 답변 토큰 수는 saveAssistantMessageAsync 내부에서 계산.
                    if (chatSeq != null) {
                        historyService.saveAssistantMessageAsync(
                                chatSeq,
                                finalAnswer,
                                searchContentJson,
                                completionTokens
                        );

                        // 답변 저장 후, 필요 시 오래된 대화를 비동기로 요약한다 (사용자 응답 지연 0)
                        summaryService.maybeSummarizeAsync(chatSeq);
                    }
                }
                emitter.complete(); // 연결 종료
            }catch(Exception e){
                log.error("Streaming 중 에러 발생", e);
                emitter.completeWithError(e);
            }
        });
    }

    /**
     * [CONTEXT]에 쓸 수 있는 토큰 여유를 계산한다.
     * = 입력 예산 - system 메시지 - 이전 대화 턴 - 현재 질문 - 메시지별 오버헤드
     */
    private int contextCapacity(int systemTokens, int questionTokens, List<LlmMessage> turns) {
        int messageCount = turns.size() + 2; // system 1 + 현재 질문 1
        return budget.inputBudget()
                - systemTokens
                - questionTokens
                - estimateTokens(turns)
                - PER_MESSAGE_OVERHEAD_TOKENS * messageCount;
    }

    private int estimateTokens(List<LlmMessage> messages) {
        int total = 0;
        for (LlmMessage message : messages) {
            total += tokenizeService.estimateTokens(message.getContent());
        }
        return total;
    }

    /**
     * 가장 오래된 턴 하나(사용자 질문 + 그에 딸린 답변)를 제거한다.
     * 남은 목록이 항상 {@code user} 메시지로 시작하도록 유지한다.
     */
    private static void dropOldestTurn(List<LlmMessage> turns) {
        if (turns.isEmpty()) {
            return;
        }
        turns.remove(0);
        while (!turns.isEmpty() && !"user".equals(turns.get(0).getRole())) {
            turns.remove(0);
        }
    }

    /** 답변의 [출처]에 적힌 {@code [문서N]} 번호를 뽑아내는 패턴 ({@code [document1]} 같은 영어 변형 포함) */
    private static final Pattern CITED_DOCUMENT_PATTERN = AnswerSections.CITATION_LABEL_PATTERN;

    /** 폴백용: 답변에서 '제N조(제목)' / '별표 N(제목)' 형태의 조문 표기를 뽑아내는 패턴 */
    private static final Pattern ARTICLE_PATTERN =
            Pattern.compile("(제\\s*\\d+\\s*조(?:의\\s*\\d+)?\\s*\\([^)]+\\)|별표\\s*\\d+(?:\\s*\\([^)]+\\))?)");

    /**
     * 답변을 근거로 화면 출처 목록의 채택 여부({@code isUsed})를 표시한다.
     *
     * <p>1순위는 LLM이 [출처]에 직접 적은 {@code [문서N]} 번호다.
     * [CONTEXT]의 문서 번호와 화면 출처 목록의 순번이 1:1로 대응하므로 정확하게 특정된다.
     *
     * <p>모델이 번호 규칙을 지키지 않아 번호가 하나도 없으면, 이전 방식인
     * 조문 표기 매칭으로 폴백한다. 프롬프트 준수율이 떨어져도 채택 표시가
     * 통째로 사라지지 않게 하기 위한 안전장치다.
     *
     * @param answer LLM 답변 전문
     * @param sources 화면 출처 목록 (순서가 [CONTEXT] 문서 번호와 일치해야 한다)
     */
    private void markUsedSources(String answer, List<Map<String, Object>> sources) {
        if (sources.isEmpty()) {
            return;
        }

        // [출처] 영역이 없으면 인용 자체가 없는 답변이다.
        // (유형1~3의 '근거가 존재하지 않습니다' 같은 고정 응답) 매칭을 시도할 필요가 없다.
        int sourceIdx = answer.lastIndexOf("[출처]");
        if (sourceIdx == -1) {
            log.info("[Sources] 답변에 [출처] 영역이 없어 채택 출처 없음으로 처리합니다.");
            return;
        }

        // [출처] 영역 이후만 본다. 본문 설명 중의 조문 언급이 섞이는 것을 막는다.
        String sourceText = answer.substring(sourceIdx);

        Set<Integer> cited = new HashSet<>();
        Matcher m = CITED_DOCUMENT_PATTERN.matcher(sourceText);
        while (m.find()) {
            cited.add(Integer.parseInt(m.group(1)));
        }

        if (!cited.isEmpty()) {
            for (int i = 0; i < sources.size(); i++) {
                sources.get(i).put("isUsed", cited.contains(i + 1));
            }

            // [CONTEXT]에 없는 번호를 인용한 경우 (모델이 번호를 지어냈을 때)
            long outOfRange = cited.stream().filter(n -> n < 1 || n > sources.size()).count();
            if (outOfRange > 0) {
                log.warn("[Sources] 답변이 인용한 문서 번호 {}건이 CONTEXT 문서 수({}건) 범위를 벗어났습니다. cited={}",
                        outOfRange, sources.size(), cited);
            }
            return;
        }

        log.warn("[Sources] 답변에 [문서N] 번호가 없어 조문 표기 매칭으로 대체합니다.");
        markUsedSourcesByArticle(sourceText, sources);
    }

    /**
     * 폴백: 답변에 적힌 조문 표기가 문서 본문에 들어있는지로 채택 여부를 추정한다.
     * 표기 방식에 따라 과다·누락 매칭이 생길 수 있어 번호 방식이 실패했을 때만 쓴다.
     */
    private void markUsedSourcesByArticle(String sourceText, List<Map<String, Object>> sources) {
        List<String> keywords = new ArrayList<>();
        Matcher m = ARTICLE_PATTERN.matcher(sourceText);
        while (m.find()) {
            // 띄어쓰기 차이로 매칭이 어긋나지 않도록 양쪽 모두 공백을 제거해 비교한다.
            keywords.add(m.group(1).replaceAll("\\s+", ""));
        }

        for (Map<String, Object> source : sources) {
            String content = (String) source.get("content");
            String noSpaceContent = (content == null ? "" : content).replaceAll("\\s+", "");

            boolean matched = false;
            for (String keyword : keywords) {
                if (noSpaceContent.contains(keyword)) {
                    matched = true;
                    break;
                }
            }
            source.put("isUsed", matched);
        }
    }

    /**
     * 리랭킹된 문서 목록으로 LLM에 전달할 [CONTEXT] 블록을 구성한다.
     * 추정 토큰이 {@code capTokens}를 넘기 직전까지만 담고, 뒤쪽(하위 랭크) 문서는 제외한다.
     * 단, 최소 1건은 반드시 담아 근거 없는 요청이 나가지 않게 한다.
     *
     * @param items 리랭킹 순서의 근거 후보 문서 목록 (상위 랭크 우선)
     * @param capTokens 이 블록에 허용된 토큰 상한
     * @return 조립된 [CONTEXT] 블록
     */
    private ContextBlock buildContext(List<RetrievalService.RetrievalItem> items, int capTokens) {
        String head = "[CONTEXT]";
        StringBuilder sb = new StringBuilder(head);
        int total = tokenizeService.estimateTokens(head);
        int used = 0;

        for (RetrievalService.RetrievalItem item : items) {
            // 문서 번호는 1부터 연속으로 붙인다.
            // 이 번호가 출처 후보 목록의 순번(sourceCandidates 인덱스+1)과 1:1로 대응하므로,
            // 중간에 건너뛰면 채택 표시가 엉뚱한 문서에 붙는다.
            String segment = renderDocument(item, used + 1);
            if (segment == null) {
                break;   // 앞의 필터를 통과한 목록이라 발생하지 않지만, 번호가 어긋나느니 중단한다.
            }
            int tokens = tokenizeService.estimateTokens(segment);
            if (used > 0 && total + tokens > capTokens) {
                log.info("[Context] 토큰 예산({}) 때문에 문서 {}건만 사용 (전체 {}건)", capTokens, used, items.size());
                break;
            }
            sb.append(segment);
            total += tokens;
            used++;
        }
        return new ContextBlock(sb.toString(), used, total);
    }

    /**
     * 문서 한 건을 [CONTEXT] 조각으로 렌더링한다.
     * 각 문서 앞에 문서명 라벨을 붙여 LLM이 출처를 정확히 인용하도록 돕는다.
     *
     * <p>맨 앞에는 {@code [문서N]} 번호를 붙인다. LLM이 답변의 [출처]에 이 번호를 그대로
     * 적도록 시스템 프롬프트에서 요구하며, 그 번호로 실제 채택 문서를 특정한다.
     * (답변 본문을 정규식으로 역추적하던 방식보다 정확하다)
     *
     * @param item 렌더링할 문서
     * @param number 1부터 시작하는 문서 번호. 화면 출처 목록의 순번과 일치한다.
     * @return 문서 조각. 본문이 없으면 {@code null}
     */
    private String renderDocument(RetrievalService.RetrievalItem item, int number) {
        DocumentChunk chunk = item.chunk();
        if (chunk == null || !chunk.hasContent()) {
            return null;
        }

        // 각 문서 앞에 번호와 메타데이터 라벨을 붙여 LLM이 출처를 정확히 인용하도록 돕는다.
        // [구분]은 이 문서가 직접 검색된 것인지, 검색 결과의 인접 구간인지를 알려준다.
        //
        // 필드 의미에 주의한다.
        //  - 문서번호 : 청크의 식별자({문서ID}_{청크번호})
        //  - 버전     : 문서의 '버전(개정차수)'다. 조문번호가 아니다.
        // 조문번호(제N조)는 메타데이터에 없고 오직 본문 텍스트에만 있으므로,
        // 조문 라벨을 만들어 붙이면 버전을 조문번호로 오인시키게 된다. 절대 만들지 않는다.
        StringBuilder sb = new StringBuilder();
        sb.append("[문서").append(number).append("]");
        sb.append("[구분: ").append(originLabel(item.origin())).append("]");
        if (StringUtils.isNotEmpty(chunk.title())) {
            sb.append("[문서명: ").append(chunk.title()).append("]");
        }
        sb.append("[문서번호: ").append(chunk.chunkId()).append("]");
        if (StringUtils.isNotEmpty(chunk.version())) {
            sb.append("[버전: ").append(chunk.version()).append("]");
        }
        if (StringUtils.isNotEmpty(chunk.effectiveDate())) {
            sb.append("[시행일자: ").append(chunk.effectiveDate()).append("]");
        }
        sb.append("\n");
        sb.append(chunk.content()).append("\n");
        return sb.toString();
    }

    /**
     * 문서 출처 구분을 [CONTEXT] 라벨 문구로 바꾼다.
     *
     * <p>인접 구간은 검색 결과 <b>바로 앞/뒤</b>에 배치되므로, 문구에 배치 방향을 함께 적어
     * 어느 문서와 이어지는지 순서만으로 알 수 있게 한다.</p>
     */
    private static String originLabel(RetrievalService.Origin origin) {
        return switch (origin) {
            case HIT -> "검색결과";
            case PREV -> "인접구간 · 바로 다음 문서에서 이어짐";
            case NEXT -> "인접구간 · 바로 앞 문서에서 이어짐";
        };
    }

    /**
     * vLLM으로 실제 전송되는 요청(프롬프트 전체)을 로그로 남긴다.
     * 매 채팅마다 어떤 프롬프트가 나갔는지 확인하는 용도이며, API 키는 헤더에만 있어 본문에는 포함되지 않는다.
     *
     * @param url 요청을 전송할 LLM 엔드포인트
     * @param payload 전송할 Chat Completions 요청
     */
    private void logOutbound(String url, LlmChatRequest payload) {
        try {
            // 전체 프롬프트는 대용량 + 사용자 질의/문서 평문이 포함되므로 DEBUG에서만 남긴다.
            // (개발: logging.level.com.ragchat=DEBUG 로 확인 / 운영: INFO 라 숨김)
            log.debug("[LLM] 요청 URL: {}", url);
            log.debug("[LLM] 전송 프롬프트: {}", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("[LLM] 요청 직렬화 로깅 실패", e);
        }
    }

    /**
     * 스트리밍으로 받은 청크를 모두 이어 붙인 LLM 최종 답변을 로그로 남긴다.
     * 청크 단위 원문은 log.debug("[LLM] 응답 청크")로 따로 확인할 수 있다.
     *
     * @param answer 이어 붙인 최종 답변 전문
     * @param completionTokens 응답 usage에서 받은 답변 토큰 수(없으면 0)
     */
    private void logInbound(String answer, int completionTokens) {
        // 응답 길이/토큰 수는 운영 지표로 유용해 INFO 유지, 답변 전문은 대용량이라 DEBUG.
        log.info("[LLM] 응답 길이: {}자, completionTokens: {}", answer.length(), completionTokens);
        log.debug("[LLM] 응답 전문: {}", answer);
    }
}
