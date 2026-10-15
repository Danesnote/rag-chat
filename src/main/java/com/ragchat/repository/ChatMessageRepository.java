package com.ragchat.repository;

import com.ragchat.entity.ChatListEntity;
import com.ragchat.entity.ChatMessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    // ChatListEntity 객체를 기준으로 조회 (필드명이 chatSeq이므로)
    List<ChatMessageEntity> findByChatSeqOrderByRegDateAsc(ChatListEntity chatListEntity);
    // 특정 방(chatSeq)의 메시지를 순서대로(Asc) 조회
    List<ChatMessageEntity> findAllByChatSeq_IdOrderByIdAsc(Long chatSeq);

    // 특정 방에서 특정 메시지 seq 이후(미요약 구간) 메시지를 순서대로 조회
    List<ChatMessageEntity> findAllByChatSeq_IdAndIdGreaterThanOrderByIdAsc(Long chatSeq, Long messageSeq);

    @Query("SELECT m FROM ChatMessageEntity m WHERE m.chatSeq.id = :chatSeq ORDER BY m.regDate DESC")
    Slice<ChatMessageEntity> findLatestMessages(@Param("chatSeq") Long chatSeq, Pageable pageable);

    @Modifying
    @Query("DELETE FROM ChatMessageEntity m WHERE m.chatSeq.id = :chatSeq")
    int deleteAllByChatSeq(@Param("chatSeq") Long chatSeq);
}
