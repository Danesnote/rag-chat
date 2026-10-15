package com.ragchat.controller;

import com.ragchat.dto.document.DocumentCreateRequest;
import com.ragchat.dto.document.DocumentCreateResponse;
import com.ragchat.retrieval.ChunkRepository;
import com.ragchat.service.DocumentIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 검색 대상 문서 관리 API.
 *
 * <pre>
 *   POST   /api/documents          JSON 본문으로 적재
 *   POST   /api/documents/upload   .txt / .md 파일 업로드로 적재 (multipart)
 *   GET    /api/documents          적재된 문서 목록
 *   DELETE /api/documents/{id}     문서와 청크 삭제
 * </pre>
 *
 * <p>관리용 API 이므로 {@code rag.ingest.enabled=false} 이면 모두 403 을 돌려준다.
 * 운영에서는 끄거나, 앞단(게이트웨이·Spring Security)에서 관리자만 접근하도록 막아야 한다.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/documents")
public class DocumentApiController {

    private final DocumentIngestService ingestService;

    @Value("${rag.ingest.enabled:false}")
    private boolean ingestEnabled;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentCreateResponse> create(@RequestBody DocumentCreateRequest request) {
        checkEnabled();
        return ResponseEntity.status(HttpStatus.CREATED).body(ingest(request, null));
    }

    /**
     * 텍스트 파일을 적재한다. 문서명을 생략하면 확장자를 뺀 파일명을 쓴다. (UTF-8 가정)
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentCreateResponse> upload(@RequestParam("file") MultipartFile file,
                                                         @RequestParam(value = "title", required = false) String title,
                                                         @RequestParam(value = "version", required = false) String version,
                                                         @RequestParam(value = "effectiveDate", required = false) String effectiveDate) {
        checkEnabled();
        String filename = file.getOriginalFilename() == null ? "document.txt" : file.getOriginalFilename();
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".txt") && !lower.endsWith(".md")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ".txt 또는 .md 파일만 적재할 수 있습니다.");
        }
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "파일을 읽을 수 없습니다.");
        }
        String resolvedTitle = (title == null || title.isBlank())
                ? filename.replaceFirst("\\.[^.]+$", "")
                : title;

        DocumentCreateRequest request = new DocumentCreateRequest(resolvedTitle, version, effectiveDate, content);
        return ResponseEntity.status(HttpStatus.CREATED).body(ingest(request, filename));
    }

    @GetMapping
    public List<ChunkRepository.DocumentSummary> list() {
        checkEnabled();
        return ingestService.list();
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable long documentId) {
        checkEnabled();
        if (!ingestService.delete(documentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다.");
        }
        return ResponseEntity.noContent().build();
    }

    private DocumentCreateResponse ingest(DocumentCreateRequest request, String sourceName) {
        try {
            return ingestService.ingest(request, sourceName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private void checkEnabled() {
        if (!ingestEnabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "문서 관리 API 가 비활성화되어 있습니다. (rag.ingest.enabled)");
        }
    }
}
