package com.ragchat.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 루트("/") 진입 처리 전용 컨트롤러.
 *
 * <p>기존에는 templates/index.html 이 웰컴 페이지 역할을 했다.
 * Spring Boot 는 이름이 {@code index} 인 템플릿을 자동으로 루트에 매핑하는데,
 * 해당 파일 내용이 스텁이라 루트로 들어온 사용자에게 빈 화면이 노출됐다.
 * 템플릿을 제거하고 이 컨트롤러에서 곧바로 채팅 화면으로 넘긴다.
 *
 * <p>다른 컨트롤러들은 클래스 레벨에 {@code @RequestMapping} 프리픽스가 있어
 * 루트를 매핑할 수 없으므로 별도 컨트롤러로 분리했다.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String root() {
        return "redirect:/chat/main";
    }
}
