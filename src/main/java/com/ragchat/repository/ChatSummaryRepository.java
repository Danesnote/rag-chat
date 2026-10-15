package com.ragchat.repository;

import com.ragchat.entity.ChatSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ChatSummaryRepository extends JpaRepository<ChatSummaryEntity, Long> {

    // 특정 방의 최신(버전 최대) 요약 1건
    Optional<ChatSummaryEntity> findTopByChatSeqOrderBySummaryVersionDesc(Long chatSeq);

    @Modifying
    @Query("DELETE FROM ChatSummaryEntity s WHERE s.chatSeq = :chatSeq")
    int deleteAllByChatSeq(@Param("chatSeq") Long chatSeq);
}
