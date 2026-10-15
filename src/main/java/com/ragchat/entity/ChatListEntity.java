package com.ragchat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@Table(name = "demo_chat_list")
public class ChatListEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_seq")
    private Long id;

    @CreatedDate
    @Column(name = "reg_date", updatable = false)
    private LocalDateTime regDate;

    // 제목은 첫 질문 원문을 그대로 저장한다(DB 컬럼 타입 TEXT). 화면에서만 잘라 보여준다.
    @Column(name = "title", columnDefinition = "TEXT")
    private String title;

    @Column(name = "user_seq", nullable = false)
    private Long userSeq;

    @Builder.Default
    @Column(name = "archive_yn", length = 8, columnDefinition = "varchar(8) default 'N'")
    private String archiveYn = "N";

    // 마지막 대화(메시지) 발생 시각. 목록을 '최근 대화 순'으로 정렬하는 데 사용한다.
    @Column(name = "mod_date")
    private LocalDateTime modDate;

    public void changeTitle(String title) {
        this.title = title;
    }

    public void changeArchiveYn(String archiveYn) {
        this.archiveYn = archiveYn;
    }
}
