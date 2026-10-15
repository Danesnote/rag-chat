package com.ragchat.controller;

import com.ragchat.dto.search.SearchHitResponse;
import com.ragchat.dto.search.SearchRequest;
import com.ragchat.service.RetrievalService;
import com.ragchat.util.InputGuard;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 검색 단독 실행 API. LLM 을 호출하지 않고 어떤 청크가 근거로 뽑히는지 확인할 때 쓴다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/search")
public class SearchApiController {

    private final RetrievalService retrievalService;

    @Value("${rag.retrieval.mode:2}")
    private int defaultMode;

    @Value("${sso.required:false}")
    private boolean ssoRequired;

    @PostMapping
    public List<SearchHitResponse> search(@RequestBody SearchRequest request, HttpSession session) {
        if (ssoRequired && SsoController.getUserId(session) == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        if (request.query() == null || request.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "질의를 입력해 주세요.");
        }
        if (InputGuard.containsMarkup(request.query())) {
            log.warn("[InputGuard] 마크업 포함 검색 질의 차단");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, InputGuard.REJECT_MESSAGE);
        }
        int mode = request.mode() != null ? request.mode() : defaultMode;
        return retrievalService.retrieve(request.query().trim(), mode).stream()
                .map(SearchHitResponse::from)
                .toList();
    }
}
