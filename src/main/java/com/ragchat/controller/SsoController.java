package com.ragchat.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 외부 시스템(SSO)에서 넘어오는 진입점.
 *
 * <p>사용 흐름
 * <pre>
 *   외부 → GET /sso?ssoToken=1234
 *        → 토큰을 서버 세션에 저장
 *        → 302 redirect: /chat/main   (URL 에 토큰이 남지 않음)
 * </pre>
 *
 * <p><b>주의</b> 토큰을 {@code Model} 에 담아서는 안 된다.
 * Spring 은 {@code redirect:} 뷰를 처리할 때 모델의 원시 타입 속성을
 * 리다이렉트 URL 의 쿼리 문자열로 자동 부착한다.
 * 즉 {@code model.addAttribute("ssoToken", ...)} 를 하면
 * {@code /chat/main?ssoToken=1234} 가 되어 감추려던 값이 그대로 노출된다.
 * 값은 반드시 세션(또는 flash attribute)으로만 전달한다.
 */
@Slf4j
@Controller
public class SsoController {

    /** 세션에 SSO 토큰을 보관할 때 쓰는 키 */
    public static final String SESSION_SSO_TOKEN = "ssoToken";

    /**
     * 세션에 <b>사용자 ID</b>를 보관할 때 쓰는 키.
     *
     * <p>SSO 인증을 통과하면 얻는 사용자 식별값(예: {@code "99999"})이며, 이 값이
     * {@code chat_list.user_seq} 가 되어 대화방을 사용자별로 구분한다.
     * 포털이 넘겨준 {@code ssoToken} 과는 다른 값이다. 토큰은 1회용 입장권이고,
     * 이 값이 실제 신원이다. <b>인증 여부 판단과 사용자 식별은 모두 이 키를 기준으로 한다.</b>
     */
    public static final String SESSION_USER_ID = "ssoUserId";

    /** 화면에서 alert 으로 띄울 메시지를 담는 flash attribute 키 */
    public static final String FLASH_SSO_ALERT = "ssoAlert";

    /** 인증 실패 안내 페이지 경로. 채팅 화면을 대신 내려주는 곳이다. */
    public static final String FAIL_PATH = "/sso/fail";

    /** 인증 실패 후 "확인" 을 눌렀을 때 이동할 주소 */
    @Value("${sso.portal-url:/sso}")
    private String portalUrl;

    /**
     * 인증 강제 여부.
     *
     * <p>{@code false} 면 SSO 관련 검증을 전부 건너뛴다.
     * dev 환경은 SSO 서버에 접근할 수 없으므로 이 값을 {@code false} 로 둔다.
     */
    @Value("${sso.required:false}")
    private boolean ssoRequired;

    @GetMapping("/sso")
    public String ssoEntry(@RequestParam(value = "ssoToken", required = false) String ssoToken,
                           HttpServletRequest request,
                           RedirectAttributes redirectAttributes) {

        // 0) 인증 비활성(dev): 토큰 검사도, SSO 서버 호출도 하지 않는다.
        //    토큰이 실려 오면 세션에 담아 두어 사용자별 동작을 테스트할 수 있게 한다.
        if (!ssoRequired) {
            log.warn("[SSO] 인증이 비활성 상태입니다(sso.required=false). 검증 없이 진입합니다.");

            if (ssoToken != null && !ssoToken.isBlank()) {
                HttpSession devSession = request.getSession(true);
                devSession.setAttribute(SESSION_SSO_TOKEN, ssoToken.trim());
                // 토큰을 사용자 ID로 취급해 /sso?ssoToken=2 처럼 사용자별 동작을 시험할 수 있게 한다.
                devSession.setAttribute(SESSION_USER_ID, ssoToken.trim());
            }
            return "redirect:/chat/main";
        }

        // 1) 토큰 자체가 없는 경우
        if (ssoToken == null || ssoToken.isBlank()) {
            log.warn("[SSO] ssoToken 없이 진입했습니다.");
            redirectAttributes.addFlashAttribute(FLASH_SSO_ALERT, "SSO 토큰이 전달되지 않았습니다.\n포털에서 다시 접속해 주세요.");
            return "redirect:" + FAIL_PATH;
        }

        final String token = ssoToken.trim();

        // 2) 인증 확인 및 사용자 ID 확보
        //    SSO 연동 결과로 사용자 ID(uid)를 받아오며, uid 를 받았다는 것이 곧 인증 성공이다.
        final String uid = resolveUserId(token);
        final boolean authenticated = (uid != null && !uid.isEmpty());

        if (!authenticated) {
            log.warn("[SSO] 토큰 인증에 실패했습니다. (사용자 ID를 얻지 못함)");
            redirectAttributes.addFlashAttribute(FLASH_SSO_ALERT, "인증에 실패했습니다.\n포털에서 다시 접속해 주세요.");
            // 인증 실패 시 아무것도 세션에 저장하지 않는다. 기존 세션도 건드리지 않는다.
            return "redirect:" + FAIL_PATH;
        }

        // 3) 인증 통과 → 세션에 사용자 ID 저장
        final HttpSession current = request.getSession(false);
        final String previousUserId = (current == null)
                ? null
                : (String) current.getAttribute(SESSION_USER_ID);

        // 다른 사용자로 진입하는 경우에만 세션을 교체한다.
        // (세션 고정 공격 방어: 공격자가 미리 심어둔 세션 ID 를 그대로 인증 상태로 승격시키지 않는다)
        // 비교 기준은 토큰이 아니라 사용자 ID 다. 토큰은 접속할 때마다 새로 발급되는 1회용 값이라
        // 토큰으로 비교하면 같은 사람이 재접속할 때마다 세션이 날아간다.
        if (current != null && !uid.equals(previousUserId)) {
            current.invalidate();
        }

        final HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_SSO_TOKEN, token);
        session.setAttribute(SESSION_USER_ID, uid);

        // 토큰 원문은 로그에 남기지 않는다. (로그 파일 유출 시 그대로 재사용 가능)
        // 사용자 ID 는 대화방 소유자 추적에 필요하므로 남긴다.
        log.info("[SSO] 인증 완료. userId={}, sessionId={}", uid, session.getId());

        return "redirect:/chat/main";
    }

    /**
     * 인증 실패 안내 페이지.
     *
     * <p>채팅 화면 대신 이 페이지를 내려준다. 화면에 채팅 기능이 아예 없으므로
     * 개발자도구로 모달을 지워도 사용할 수 있는 것이 없다.
     */
    @GetMapping(FAIL_PATH)
    public String ssoFail(Model model) {
        // ssoAlert 은 flash attribute 로 자동 주입된다.
        // 주소를 직접 입력해 들어온 경우엔 값이 없으므로 화면에서 기본 문구로 대체한다.
        model.addAttribute("portalUrl", portalUrl);
        return "/views/sso/fail";
    }

    /**
     * SSO 토큰으로 사용자 ID를 조회한다.
     *
     * <p><b>사용하는 SSO 솔루션의 연동 코드를 넣는 유일한 지점이다.</b> 이 메소드만 교체하면 되고,
     * 호출부와 세션 처리는 손댈 필요가 없다.
     *
     * <pre>
     * // 예: SSO 클라이언트가 사용자 ID 문자열을 돌려주는 경우
     * String uid = ssoAuthClient.getUserId(ssoToken);
     * return uid;
     * </pre>
     *
     * <p>이 메소드는 {@code sso.required=true} 일 때만 호출되므로,
     * SSO 서버에 접근할 수 없는 환경에서 호출이 발생하는 일은 없다.
     *
     * @param ssoToken 포털이 전달한 토큰(공백 제거 완료, 비어 있지 않음)
     * @return 사용자 ID. 인증에 실패했거나 사용자를 찾지 못하면 {@code null} 또는 빈 문자열
     */
    private String resolveUserId(String ssoToken) {
        // TODO 사용하는 SSO 솔루션의 토큰 검증 API 호출로 교체할 것.
        //      현재는 토큰을 그대로 사용자 ID로 취급한다. (연동 전 임시 동작)
        return ssoToken;
    }

    /**
     * 현재 세션에 저장된 SSO 토큰을 반환한다. 없으면 {@code null}.
     *
     * <p>토큰은 입장권일 뿐이며, 사용자 식별에는 {@link #getUserId(HttpSession)} 를 쓴다.
     */
    public static String getSsoToken(HttpSession session) {
        return session == null ? null : (String) session.getAttribute(SESSION_SSO_TOKEN);
    }

    /**
     * 현재 세션에 저장된 사용자 ID를 반환한다. 없으면 {@code null}.
     *
     * <p>이 값이 있으면 인증을 통과한 것이고, 없으면 미인증이다.
     * 사용자 번호로 바꾸는 곳은 {@code ChatApiController#currentUserSeq(HttpSession)} 하나다.
     */
    public static String getUserId(HttpSession session) {
        return session == null ? null : (String) session.getAttribute(SESSION_USER_ID);
    }
}
