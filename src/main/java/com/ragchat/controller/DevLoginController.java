package com.ragchat.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 개발(dev) 전용 로그인 페이지.
 *
 * <p><b>{@code @Profile("dev")} 이므로 dev 프로파일에서만 빈이 생성된다.</b>
 * local / test / prod 프로파일에서는 이 컨트롤러 자체가 로딩되지 않아 {@code /dev/login}
 * 경로가 존재하지 않는다. 즉 운영 배포물에서는 있으나 마나 한 것이 아니라 <b>아예 없다.</b>
 * (숨겨둔 백도어가 아니라, 프로파일로 강제 차단되는 개발 편의 기능이다.)
 *
 * <p>SSO 포털을 거치지 않고 properties 의 개발 계정(id/pw)으로 세션에 사용자 토큰을 심어
 * 채팅 화면으로 진입시킨다. 세션 키는 {@link SsoController#SESSION_SSO_TOKEN} 과 동일하므로
 * 이후 로직({@code currentUserSeq} 등)은 SSO 로그인과 완전히 동일하게 동작한다.
 *
 * <p>보안 메모
 * <ul>
 *   <li>비밀번호가 비어 있으면({@code dev.login.password} 미설정) 로그인 자체를 비활성화한다.</li>
 *   <li>자격증명은 소스가 아니라 dev 프로파일 properties 에서 주입한다.</li>
 *   <li>id/pw 비교는 타이밍 공격을 줄이기 위해 상수 시간 비교를 사용한다.</li>
 *   <li>로그인 성공 시 세션 고정 공격 방어를 위해 세션을 새로 발급한다.</li>
 * </ul>
 */
@Slf4j
@Profile("dev")
@Controller
@RequestMapping("/dev/login")
public class DevLoginController {

    @Value("${dev.login.id:}")
    private String loginId;

    @Value("${dev.login.password:}")
    private String loginPassword;

    /** 로그인 성공 시 세션에 저장할 사용자 번호(숫자). currentUserSeq 가 이 값으로 사용자별 데이터를 분리한다. */
    @Value("${dev.login.user-seq:1}")
    private String loginUserSeq;

    @GetMapping
    public String form(Model model) {
        model.addAttribute("devLoginDisabled", isDisabled());
        return "/views/dev/login";
    }

    @PostMapping
    public String submit(@RequestParam(value = "id", required = false) String id,
                         @RequestParam(value = "password", required = false) String password,
                         HttpServletRequest request,
                         RedirectAttributes ra) {

        // 안전장치: 비밀번호 미설정이면 로그인 자체를 막는다. (기본 비밀번호로 인한 사고 방지)
        if (isDisabled()) {
            log.warn("[DevLogin] dev.login.password 미설정 - 개발 로그인 비활성");
            ra.addFlashAttribute("error", "개발 로그인이 비활성화되어 있습니다. (dev.login.password 미설정)");
            return "redirect:/dev/login";
        }

        boolean ok = safeEquals(loginId, id) & safeEquals(loginPassword, password);
        if (!ok) {
            log.warn("[DevLogin] 로그인 실패 - 입력 id={}", id);
            ra.addFlashAttribute("error", "아이디 또는 비밀번호가 올바르지 않습니다.");
            return "redirect:/dev/login";
        }

        // 세션 고정 공격 방어: 기존 세션을 버리고 새 세션을 발급한다.
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(SsoController.SESSION_SSO_TOKEN, loginUserSeq.trim());
        log.info("[DevLogin] 개발 로그인 성공 - userSeq={}, sessionId={}", loginUserSeq, session.getId());

        return "redirect:/chat/main";
    }

    private boolean isDisabled() {
        return loginPassword == null || loginPassword.isBlank();
    }

    /** 타이밍 공격을 줄이기 위한 상수 시간 비교. */
    private boolean safeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
