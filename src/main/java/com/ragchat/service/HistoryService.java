package com.ragchat.service;

import com.ragchat.entity.ChatListEntity;
import com.ragchat.entity.ChatMessageEntity;
import com.ragchat.repository.ChatListRepository;
import com.ragchat.repository.ChatMessageRepository;
import com.ragchat.repository.ChatSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final ChatListRepository listRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatSummaryRepository summaryRepository;

    //메시지 토큰 수 계산
    private final TokenizeService tokenizeService;

    /**
     * 채팅방 생성
     * @param userSeq 사용자 번호 (세션의 인증 정보에서 얻는다)
     * @param firstQuestion 첫 질문 (제목용)
     * @return 생성된 채팅방의 chatListId
     */
    @Transactional
    public Long createChatList(Long userSeq, String firstQuestion) {

        // 제목은 첫 질문 원문을 그대로 저장한다(title 컬럼은 TEXT).
        // 목록에 20자로 줄여 보여주는 것은 화면(main.js truncateTitle)에서 처리한다.
        String title = firstQuestion;

        ChatListEntity chatList = ChatListEntity.builder()
                .userSeq(userSeq)
                .title(title)
                .archiveYn("N")
                .modDate(LocalDateTime.now())   // 생성 시각을 초기 활동시각으로 설정
                .build();

        // 4. 저장 및 ID 반환
        return listRepository.save(chatList).getId();
    }

    /**
     * [사용자 질문 저장] 비동기로 토큰 수를 계산하여 함께 저장한다.
     *
     * @param chatSeq 채팅방 식별자
     * @param content 사용자 질문 원문
     */
    @Async
    @Transactional
    public void saveUserMessageAsync(Long chatSeq, String content) {
        persistMessage(chatSeq, ChatMessageEntity.MessageRole.USER, content, "", tokenizeService.countTokensSafe(content));
    }

    /**
     * [AI 답변 저장] 비동기로 저장한다. 스트리밍 응답의 usage에서 받은 토큰 수가 있으면 그대로 사용하고,
     * 없으면(0) {@code /tokenize}로 계산하여 저장한다.
     *
     * @param chatSeq 채팅방 식별자
     * @param content AI 답변 원문
     * @param searchContentJson 답변 생성에 사용한 검색 결과(출처) JSON
     * @param usageTokenCount 스트리밍 usage에서 받은 답변 토큰 수. 0 이하이면 tokenize로 폴백한다.
     */
    @Async
    @Transactional
    public void saveAssistantMessageAsync(Long chatSeq, String content, String searchContentJson, int usageTokenCount) {
        int tokenCount = usageTokenCount > 0 ? usageTokenCount : tokenizeService.countTokensSafe(content);
        persistMessage(chatSeq, ChatMessageEntity.MessageRole.ASSISTANT, content, searchContentJson, tokenCount);
    }

    /**
     * 메시지를 실제로 저장한다. (토큰 수까지 포함)
     * 비동기 로직이므로 저장 실패가 사용자 응답에 영향을 주지 않도록 예외를 삼킨다.
     */
    private void persistMessage(Long chatSeq, ChatMessageEntity.MessageRole role, String content, String searchContentJson, int tokenCount) {
        try {
            // ID만으로 프록시 객체를 조회해 연관관계만 설정 (조회 쿼리 절약)
            ChatListEntity chatList = listRepository.getReferenceById(chatSeq);

            ChatMessageEntity chatMessage = ChatMessageEntity.builder()
                    .chatSeq(chatList)
                    .role(role)
                    .content(content)
                    .searchContent(searchContentJson)
                    .tokenCount(tokenCount)
                    .build();

            messageRepository.save(chatMessage);

            // 대화방의 마지막 활동시각 갱신 → 좌측 목록이 '최근 대화 순'으로 정렬됨
            listRepository.touchModDate(chatSeq, LocalDateTime.now());

            log.info("[Async] 메시지 저장 완료 - RoomId: {}, Role: {}, tokenCount: {}", chatSeq, role, tokenCount);

        } catch (Exception e) {
            log.error("[Async] 메시지 저장 실패: {}", e.getMessage());
        }
    }

    // 대화 이력 목록 가져오기
    public List<ChatListEntity> getChatHistory(Long userSeq) {
        return listRepository.findAllByUserSeqOrderByRegDateDesc(userSeq);
    }

    // 특정 방의 대화 내용(메시지들) 가져오기
    public List<ChatMessageEntity> getChatMessages(Long chatSeq) {
        // (선택사항) 본인 방인지 확인하는 검증 로직을 넣을 수도 있음
        return messageRepository.findAllByChatSeq_IdOrderByIdAsc(chatSeq);
    }

    public Slice<ChatMessageEntity> getChatMessagesByPage(Long chatSeq, int page) {
        // 10세트(질문+답변)라고 가정하면 메시지는 20개 정도가 적당
        int pageSize = 20;
        PageRequest pageable = PageRequest.of(page, pageSize);

        // 1. 최신순으로 가져옴 (예: 100번~81번)

        // 2. 리스트를 뒤집음 (예: 81번~100번 순서로 변경)
        // Slice는 불변 객체일 수 있으므로 내용물만 꺼내서 뒤집고 다시 감싸거나,
        // Controller에서 처리하기 쉽게 내용물 리스트를 반환하는 DTO 구조를 추천하지만,
        // 여기서는 간단히 리스트 순서를 뒤집는 로직을 Controller에서 수행하도록 하겠습니다.

        return messageRepository.findLatestMessages(chatSeq, pageable);
    }

    // 대화 이력 목록 가져오기
    public Slice<ChatListEntity> getChatHistoryByArchiveYn(Long userSeq, String archiveYn, Pageable pageable) {
        return listRepository.findAllByUserSeqAndArchiveYnOrderByModDateDescIdDesc(userSeq, archiveYn, pageable);
    }

    // 대화 이력 목록 가져오기
    public List<ChatListEntity> getChatHistoryById(Long userSeq, Long id) {
        return listRepository.findAllByUserSeqAndIdOrderByRegDateDesc(userSeq, id);
    }

    /**
     * 아카이브 보관 / 취소
     * @param chatId 채팅방 ID
     * @param archiveYn 아카이브 여부
     */
    @Transactional
    public void updateArchiveStatus(Long chatId, String archiveYn) {
        // 채팅방이 없으면 여기서 IllegalArgumentException 에러가 터지고, 바로 Controller의 catch 블록으로 날아갑니다.
        ChatListEntity chat = listRepository.findById(chatId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않거나 이미 삭제된 채팅내역입니다."));

        // 에러가 안 터졌다면 값을 변경합니다.
        chat.changeArchiveYn(archiveYn);
    }

    /**
     * 채팅방 이름 변경
     * @param chatId 채팅방 ID
     * @param newTitle 변경할 이름
     */
    @Transactional
    public void updateChatTitle(Long chatId, String newTitle) {
        ChatListEntity chat = listRepository.findById(chatId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않거나 이미 삭제된 채팅내역입니다."));

        // 채팅방 변경 로직 호출
        chat.changeTitle(newTitle);
    }

    @Transactional
    public void deleteChat(Long chatId) {
        ChatListEntity chat = listRepository.findById(chatId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않거나 이미 삭제된 채팅방입니다."));

        summaryRepository.deleteAllByChatSeq(chatId);
        messageRepository.deleteAllByChatSeq(chatId);
        listRepository.delete(chat);
    }
}
