package com.ragchat.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/chat")
public class ChatViewController {

    /**
     * 인증 강제 여부.
     *
     * <p>{@code true} 면 세션에 SSO 토큰이 없을 때 채팅 화면을 내려주지 않는다.
     * 화면을 보내고 모달로 가리는 방식은 개발자도구로 모달만 지우면 뚫리므로,
     * <b>HTML 자체를 주지 않는 것</b>이 유일한 차단 방법이다.
     *
     * <p>dev 에서는 {@code false} 로 두어 SSO 없이도 로컬 개발이 가능하게 한다.
     */
    @Value("${sso.required:false}")
    private boolean ssoRequired;

    /**
     * 검색 방식(하이브리드 / 하이브리드 + 인접 청크) 선택 UI 노출 여부.
     *
     * <p>{@code local}·{@code test} 에서는 {@code true} 로 두어 화면에서 전환하며 비교할 수 있게 하고,
     * {@code dev}·{@code prod} 에서는 {@code false} 로 두어 사용자에게 노출하지 않는다.
     *
     * <p>기본값은 {@code false} 다. 프로파일에 설정을 빠뜨렸을 때 내부 전환 UI가
     * 사용자에게 노출되는 것보다, 숨겨져서 개발자가 알아채는 편이 안전하다.
     */
    @Value("${rag.retrieval.mode-selector.visible:false}")
    private boolean retrievalSelectorVisible;

    /** 검색 방식 기본값 (1: 하이브리드, 2: 하이브리드 + 인접 청크 확장). 선택 UI가 없을 때 프론트가 이 값을 그대로 사용한다. */
    @Value("${rag.retrieval.mode:2}")
    private int defaultRetrievalMode;

    /** 세션 만료(401) 시 alert 후 이동할 주소. SSO 실패 페이지와 같은 값을 쓴다. (SsoController#portalUrl 과 기본값 동일) */
    @Value("${sso.portal-url:/sso}")
    private String portalUrl;

    /** 화면에 표시할 어시스턴트 이름 (브라우저 제목·사이드바·인사 문구) */
    @Value("${rag.ui.assistant-name:AI 어시스턴트}")
    private String assistantName;

    /** 상단 바에 표시할 모델 이름. 실제 호출 모델(rag.llm.model)과 별개로 사용자에게 보여줄 문구다. 비우면 모델 식별자를 표시한다. */
    @Value("${rag.ui.model-label:}")
    private String modelLabel;

    @Value("${rag.llm.model:LLM}")
    private String llmModel;

    /**
     * 출처 원문 바로가기 URL 접두사. 뒤에 출처의 문서 ID(청크 ID 의 '_' 앞부분)를 붙여 연다.
     * 비워 두면 우측 패널의 바로가기 버튼을 노출하지 않는다.
     */
    @Value("${rag.ui.source-view-url:}")
    private String sourceViewUrl;

    @GetMapping({"/main/{chatSeq}", "/main"})
    public String chatMainPage(Model model,
                               @PathVariable(required = false) Long chatSeq,
                               HttpSession session) {

        if (ssoRequired && SsoController.getUserId(session) == null) {
            // 채팅 화면 대신 안내 페이지로 보낸다.
            return "redirect:" + SsoController.FAIL_PATH;
        }

        model.addAttribute("chatSeq", chatSeq);
        model.addAttribute("retrievalSelectorVisible", retrievalSelectorVisible);
        model.addAttribute("defaultRetrievalMode", defaultRetrievalMode);
        model.addAttribute("portalUrl", portalUrl);
        model.addAttribute("assistantName", assistantName);
        model.addAttribute("modelLabel", (modelLabel == null || modelLabel.isBlank()) ? llmModel : modelLabel);
        model.addAttribute("sourceViewUrl", sourceViewUrl);
        return "/views/main/chat";
    }
}
