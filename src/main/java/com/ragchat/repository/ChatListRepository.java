package com.ragchat.repository;

import com.ragchat.entity.ChatListEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ChatListRepository extends JpaRepository<ChatListEntity, Long> {
    List<ChatListEntity> findAllByUserSeqOrderByRegDateDesc(Long userSeq);

    // 좌측 목록: '최근 대화(mod_date)' 순으로 정렬 (동일 시각이면 id 내림차순)
    Slice<ChatListEntity> findAllByUserSeqAndArchiveYnOrderByModDateDescIdDesc(Long userSeq, String archiveYn, Pageable pageable);

    List<ChatListEntity> findAllByUserSeqAndIdOrderByRegDateDesc(Long userSeq, Long id);

    // 메시지 발생 시 대화방의 마지막 활동시각(mod_date)만 갱신
    @Modifying
    @Query("UPDATE ChatListEntity c SET c.modDate = :modDate WHERE c.id = :chatSeq")
    int touchModDate(@Param("chatSeq") Long chatSeq, @Param("modDate") LocalDateTime modDate);
}
