package com.ragchat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "demo_chat_summary")
public class ChatSummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_seq")
    private Long id;

    @Column(name = "chat_seq", nullable = false)
    private Long chatSeq;

    @Column(name = "summary_version", nullable = false)
    private Integer summaryVersion;

    @Column(name = "from_message_seq", nullable = false)
    private Long fromMessageSeq;

    @Column(name = "to_message_seq", nullable = false)
    private Long toMessageSeq;

    @Column(name = "summary_content", nullable = false, columnDefinition = "text")
    private String summaryContent;

    @Builder.Default
    @Column(name = "token_count", nullable = false, columnDefinition = "integer default 0")
    private Integer tokenCount = 0;

    @Builder.Default
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp(6) default current_timestamp")
    private LocalDateTime createdAt = LocalDateTime.now();
}
