package com.ragchat.controller;

import jakarta.servlet.http.HttpSession;
import com.ragchat.dto.chat.request.ChatActionRequest;
import com.ragchat.dto.chat.request.ChatHistoryRequest;
import com.ragchat.dto.chat.request.ChatRoomSliceRequest;
import com.ragchat.dto.chat.request.ChatStreamRequest;
import com.ragchat.dto.chat.response.ChatActionResponse;
import com.ragchat.dto.chat.response.ChatHistoryResponse;
import com.ragchat.dto.chat.response.ChatMessageResponse;
import com.ragchat.dto.chat.response.ChatRoomSliceResponse;
import com.ragchat.entity.ChatMessageEntity;
import com.ragchat.service.ChatService;
import com.ragchat.service.ConversationContextService;
import com.ragchat.service.ConversationContextService.ConversationMemory;
import com.ragchat.service.HistoryService;
import com.ragchat.util.InputGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/chat/api")
public class ChatApiController {

    private final ChatService chatService;
    private final HistoryService historyService;
    private final ConversationContextService conversationContextService;

    /**
     * 인증 강제 여부.
     * {@code false} 면 토큰이 없어도 {@link #devUserSeq} 로 동작한다. (dev 환경)
     */
    @Value("${sso.required:false}")
    private boolean ssoRequired;

    /** 인증 비활성 시 사용할 개발용 사용자 번호. (기본 1) */
    @Value("${sso.dev-user-seq:1}")
    private Long devUserSeq;

    /**
     * SSE 응답 제한 시간(밀리초).
     *
     * <p>검색·생성 전체가 이 시간 안에 끝나야 한다. 이벤트를 보내도 연장되지 않는 절대 시간이다.
     */
    @Value("${rag.sse.timeout-ms:180000}")
    private long sseTimeoutMs;

    /**
     * 현재 사용자 번호를 반환한다.
     *
     * <p>{@code /sso?ssoToken=...} 으로 진입할 때 세션에 저장해 둔 토큰을 사용자 번호로 사용한다.
     * {@code chat_list.user_seq} 가 {@code Long} 이라 숫자로 변환한다.
     *
     * <p>{@code sso.required=false} (dev)인 경우 토큰이 없거나 숫자가 아니어도
     * 401 을 내지 않고 개발용 사용자로 처리한다. SSO 서버에 접근할 수 없는 환경에서
     * 화면과 API 를 그대로 쓸 수 있게 하기 위한 것이다.
     *
     * <p>추후 토큰으로 사용자를 조회하는 메소드가 준비되면 <b>이 메소드 안만</b> 바꾸면 된다.
     * 호출부(아래 5개 핸들러)는 수정할 필요가 없다.
     *
     * @throws ResponseStatusException 인증이 켜져 있고 사용자를 식별할 수 없으면 401
     */
    private Long currentUserSeq(HttpSession session) {
        String userId = SsoController.getUserId(session);

        if (userId == null || userId.isBlank()) {
            if (!ssoRequired) {
                return devUserSeq;
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다. 다시 접속해 주세요.");
        }

        try {
            return Long.parseLong(userId.trim());
        } catch (NumberFormatException e) {
            // chat_list.user_seq 가 숫자형이므로 사용자 ID도 숫자여야 한다.
            // SSO가 사번이 아닌 문자열 ID(예: hong.gildong)를 주기 시작하면
            // 여기서 사용자 조회(ID -> 내부 사용자번호)를 하거나 컬럼 타입을 바꿔야 한다.
            if (!ssoRequired) {
                return devUserSeq;
            }
            log.warn("[SSO] 사용자 ID가 숫자가 아니라 사용자 번호로 변환할 수 없습니다. userId={}", userId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 사용자 정보입니다.");
        }
    }

    /**
     * 채팅 history api(좌측 메뉴용)
     * @return 일반 대화 목록
     */
    @PostMapping("/left_chat_history")
    public ResponseEntity<ChatRoomSliceResponse> leftChatHistory(@RequestBody ChatRoomSliceRequest request,
                                                                 HttpSession session) {
        Long userSeq = currentUserSeq(session);
        PageRequest pageable = PageRequest.of(request.pageNumber(), request.pageSize());
        return ResponseEntity.ok(ChatRoomSliceResponse.from(
                historyService.getChatHistoryByArchiveYn(userSeq, "N", pageable)
        ));
    }

    /**
     * 채팅 history api(좌측 메뉴용)
     * @return 아카이브 대화 목록
     */
    @PostMapping("/left_archiveyn_history")
    public ResponseEntity<ChatRoomSliceResponse> leftArchiveYnHistory(@RequestBody ChatRoomSliceRequest request,
                                                                     HttpSession session) {
        Long userSeq = currentUserSeq(session);
        PageRequest pageable = PageRequest.of(request.pageNumber(), request.pageSize());
        return ResponseEntity.ok(ChatRoomSliceResponse.from(
                historyService.getChatHistoryByArchiveYn(userSeq, "Y", pageable)
        ));
    }

    @PostMapping("/chat_action")
    public ResponseEntity<ChatActionResponse> chatAction(@RequestBody ChatActionRequest request,
                                                        HttpSession session) {
        Long userSeq = currentUserSeq(session);

        if (request.id() == null || request.id() == 0 || request.action() == null) {
            return ResponseEntity.ok(ChatActionResponse.failure("잘못된 호출입니다. 다시 시도해 주세요."));
        }

        // 소유자 조회를 겸한다. 다른 사용자의 대화 ID 를 보내면 여기서 걸러진다.
        if (historyService.getChatHistoryById(userSeq, request.id()).isEmpty()) {
            return ResponseEntity.ok(ChatActionResponse.failure("존재하지 않는 내역입니다."));
        }

        try{
            //action타입 비교
            switch (request.action()){
                case ADD:
                    historyService.updateArchiveStatus(request.id(), "Y");      //아카이브 추가
                    break;

                case CANCEL:
                    historyService.updateArchiveStatus(request.id(), "N");   //아카이브 저장 취소
                    break;

                case UPDATE:
                    if (request.title() == null || request.title().isBlank()) {
                        return ResponseEntity.ok(ChatActionResponse.failure("변경할 이름을 입력해주세요."));
                    }
                    if (InputGuard.containsMarkup(request.title())) {
                        log.warn("[InputGuard] 마크업 포함 제목 변경 차단 userSeq={}, chatSeq={}", userSeq, request.id());
                        return ResponseEntity.ok(ChatActionResponse.failure(InputGuard.REJECT_MESSAGE));
                    }
                    historyService.updateChatTitle(request.id(), request.title());   //아카이브 이름 변경
                    break;

                case DELETE:
                    historyService.deleteChat(request.id());                      //아카이브 삭제
                    break;
            }
        }catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ChatActionResponse.failure("존재하지 않는 내역입니다."));

        } catch (Exception e) {
            //DB 접속 끊김 등 알 수 없는 기타 에러가 터졌을 때
            return ResponseEntity.ok(ChatActionResponse.failure("서버 처리 중 오류가 발생했습니다."));
        }

        return ResponseEntity.ok(ChatActionResponse.success("정상 처리되었습니다."));
    }

    //SSE 스트리밍 엔드포인트
    @PostMapping("/stream")
    public SseEmitter streamQuestion(@RequestBody ChatStreamRequest request, HttpSession session){

        Long userSeq = currentUserSeq(session);

        if (request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질문을 입력해 주세요.");
        }

        // HTML 태그·스크립트가 섞인 질문은 채팅방 생성·저장·검색·LLM 호출 전에 차단한다.
        // (여기보다 뒤에서 막으면 createChatList 가 먼저 실행되어 제목으로 DB 에 남는다)
        if (InputGuard.containsMarkup(request.question())) {
            log.warn("[InputGuard] 마크업 포함 질문 차단 userSeq={}", userSeq);   // 공격 문자열 원문은 로그에 남기지 않는다
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, InputGuard.REJECT_MESSAGE);
        }

        // ---------------------------------------------------------
        // [DB Logic 1] 채팅방 관리 (동기)
        // ---------------------------------------------------------
        // 프론트에서 전달한 채팅방 번호를 사용한다. 세션에는 채팅방 번호를 저장하지 않는다.
        Long chatSeq = request.chatSeq();

        if (chatSeq == null) {
            chatSeq = historyService.createChatList(userSeq, request.question());
            log.info("새로운 채팅방 생성: {}", chatSeq);
        } else if (historyService.getChatHistoryById(userSeq, chatSeq).isEmpty()) {
            // 소유자가 아니면 존재하지 않는 것으로 취급한다. (타인의 대화방 접근 차단)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 채팅방입니다.");
        }

        // ---------------------------------------------------------
        // [Context] 멀티턴을 위한 대화 메모리 구성
        //   현재 사용자 질문은 이력 로드 이후에 저장한다. (아래 참고)
        // ---------------------------------------------------------

        // 1. 이전 대화를 컨텍스트 관리(이전 대화 요약 + 최근 원문 턴 윈도잉)로 구성 (멀티턴 핵심)
        //    현재 질문은 아직 저장 전이므로 이 메모리에는 포함되지 않는다.
        //    (새 채팅방이면 이전 메시지가 없어 빈 메모리가 된다.)
        //    system 프롬프트와 현재 질문(+검색 CONTEXT)은 ChatService에서 붙인다.
        ConversationMemory memory = conversationContextService.buildMemory(chatSeq);

        // 2. 현재 사용자 질문 저장 (이력 로드 이후에 저장해야 위 메모리와 중복되지 않는다. 토큰 수는 비동기 계산)
        historyService.saveUserMessageAsync(chatSeq, request.question());

        // ---------------------------------------------------------
        // [SSE Logic] Emitter 생성 및 스트리밍 시작
        // ---------------------------------------------------------

        // 3. SseEmitter 생성.
        //    이 제한 시간은 "비동기 처리 시작 시점부터 재는 절대 시간"이며, 중간에 이벤트를 보내도 리셋되지 않는다.
        //    질의 재작성 → 검색(모드2는 문서 수만큼 반복) → 답변 생성이 모두 직렬로 이 안에서 끝나야 한다.
        //    초과하면 AsyncRequestTimeoutException 이 발생하고, 사용자에게는 오류 없이 잘린 답변만 남는다.
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        // emitter.onTimeout(() -> emitter.complete());

        // 모델 리퀘스트
        String model = request.model();

        // 4. 비동기 서비스 호출 (검색 방식은 요청값 → 없으면 서버 기본값)
        chatService.streamAnswer(chatSeq, memory, request.question(), emitter, model, request.retrievalMode());

        // 메뉴의 대화 기록 목록 갱신을 위해 프론트엔드에 '방 ID'를 알려줘야 함 (새로 만든 방일 경우 대비)
        try {
            emitter.send(SseEmitter.event().name("chatInfo").data(chatSeq));
            System.out.println("목록 갱신!");
        } catch (IOException e) {
            emitter.completeWithError(e);
        }

        return emitter; // 연결 객체 즉시 반환
    }

    @PostMapping("/history")
    public ResponseEntity<ChatHistoryResponse> getHistory(@RequestBody ChatHistoryRequest request,
                                                          HttpSession session) {
        Long userSeq = currentUserSeq(session);

        Long chatSeq = request.chatSeq();
        if (chatSeq == null || historyService.getChatHistoryById(userSeq, chatSeq).isEmpty()) {
            return ResponseEntity.ok(ChatHistoryResponse.failure());
        }
        // 해당 방의 대화 내용(메시지)도
        List<ChatMessageEntity> messages = historyService.getChatMessages(chatSeq);

        List<ChatMessageResponse> resultMessages = messages.stream()
                .map(ChatMessageResponse::from)
                .toList();
        return ResponseEntity.ok(ChatHistoryResponse.success(resultMessages));
    }
}
