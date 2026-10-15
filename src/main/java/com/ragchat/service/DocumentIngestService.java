package com.ragchat.service;

import com.ragchat.dto.document.DocumentCreateRequest;
import com.ragchat.dto.document.DocumentCreateResponse;
import com.ragchat.retrieval.ChunkRepository;
import com.ragchat.retrieval.EmbeddingClient;
import com.ragchat.retrieval.TextChunker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 문서 적재: 본문 청킹 → 임베딩 → pgvector 저장.
 *
 * <p>임베딩을 먼저 모두 계산한 뒤 한 트랜잭션으로 저장한다. 임베딩 서버 오류로 중간에 실패하면
 * 문서 행도 남기지 않는다. (청크가 일부만 들어간 문서는 인접 청크 확장을 망가뜨린다)</p>
 */
@Slf4j
@Service
public class DocumentIngestService {

    private final ChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final TextChunker chunker;

    public DocumentIngestService(ChunkRepository chunkRepository,
                                 EmbeddingClient embeddingClient,
                                 @Value("${rag.ingest.chunk-max-chars:800}") int chunkMaxChars) {
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.chunker = new TextChunker(chunkMaxChars);
    }

    /**
     * 문서 한 건을 적재한다.
     *
     * @param request 문서 메타데이터와 본문
     * @param sourceName 원본 파일명 등 출처 표기. 없으면 {@code null}
     * @throws IllegalArgumentException 문서명·본문이 비었거나 청크가 하나도 만들어지지 않은 경우
     */
    @Transactional
    public DocumentCreateResponse ingest(DocumentCreateRequest request, String sourceName) {
        String title = trimToNull(request.title());
        if (title == null) {
            throw new IllegalArgumentException("문서명(title)을 입력해 주세요.");
        }
        List<String> chunks = chunker.split(request.content());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("본문(content)이 비어 있습니다.");
        }

        // 임베딩 입력에는 문서명을 앞에 붙인다. 청크 본문만으로는 어느 문서의 내용인지 알 수 없는 경우가 많다.
        List<String> embeddingInputs = chunks.stream().map(c -> title + "\n" + c).toList();
        List<float[]> embeddings = embeddingClient.embedAll(embeddingInputs);

        long documentId = chunkRepository.insertDocument(
                title, trimToNull(request.version()), trimToNull(request.effectiveDate()), sourceName);
        chunkRepository.insertChunks(documentId, chunks, embeddings);

        log.info("[Ingest] 문서 적재 완료 - id={}, title={}, chunks={}", documentId, title, chunks.size());
        return new DocumentCreateResponse(documentId, title, chunks.size());
    }

    public List<ChunkRepository.DocumentSummary> list() {
        return chunkRepository.findDocuments();
    }

    @Transactional
    public boolean delete(long documentId) {
        return chunkRepository.deleteDocument(documentId);
    }

    private static String trimToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
