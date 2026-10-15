package com.ragchat.service;

import io.micrometer.common.util.StringUtils;
import com.ragchat.dto.integration.llm.LlmTokenizeRequest;
import com.ragchat.dto.integration.llm.LlmTokenizeResponse;
import com.ragchat.util.HttpUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * vLLM 서버의 {@code /tokenize} API로 텍스트의 토큰 수를 계산하는 서비스.
 *
 * <p>서빙 모델의 실제 토크나이저를 사용하므로 추정치가 아닌 정확한 토큰 수를 얻는다.
 * 다만 {@code /tokenize} 호출이 실패하면 <b>0을 돌려주지 않고</b> 문자 구성 기반 추정치를 돌려준다.
 * 0을 돌려주면 컨텍스트 윈도잉(예산 합산)과 요약 트리거가 모두 "항상 예산 이하"로 판정되어
 * 조용히 무력화되고, 프롬프트가 무한히 커져 컨텍스트 초과로 이어진다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenizeService {

    private final HttpUtils httpUtils;

    @Value("${rag.llm.tokenize-url}")
    private String tokenizeUrl;
    @Value("${rag.llm.model}")
    private String model;
    @Value("${rag.llm.api-key}")
    private String apiKey;

    /**
     * 주어진 텍스트의 토큰 수를 계산한다. 실패 시 예외를 던지지 않고 {@link #estimateTokens(String)} 추정치를 반환한다.
     *
     * @param text 토큰 수를 계산할 텍스트
     * @return 토큰 수. 입력이 비었으면 0, 계산에 실패하면 추정치
     */
    public int countTokensSafe(String text) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }
        try {
            LlmTokenizeRequest request = LlmTokenizeRequest.builder()
                    .model(model)
                    .prompt(text)
                    .addSpecialTokens(false)   // 메시지 본문 자체의 토큰만 계산(BOS/EOS 제외)
                    .build();

            LlmTokenizeResponse response = httpUtils.post(
                    tokenizeUrl,
                    Map.of("Authorization", "Bearer " + apiKey),
                    request,
                    LlmTokenizeResponse.class
            );

            if (response != null && response.count() > 0) {
                return response.count();
            }
            log.warn("[Tokenize] 응답이 비어 추정치로 대체합니다.");
            return estimateTokens(text);

        } catch (Exception e) {
            log.warn("[Tokenize] 토큰 수 계산 실패, 추정치로 대체: {}", e.getMessage());
            return estimateTokens(text);
        }
    }

    /**
     * 외부 호출 없이 문자 구성으로 토큰 수를 추정한다. (약간 과대 추정 = 예산 판정에 안전한 방향)
     *
     * <p>한글/한자 등 CJK 문자는 최신 BPE 토크나이저에서 대략 문자당 1토큰에 가깝고,
     * ASCII 텍스트는 대략 4문자당 1토큰이다. 이 비율로 계산한다.
     * 프롬프트 조립 단계의 예산 검사처럼 <b>매 요청 경로에서 여러 번</b> 필요한 계산은
     * {@code /tokenize} 왕복 대신 이 메소드를 사용한다.</p>
     *
     * @param text 추정 대상 텍스트
     * @return 추정 토큰 수. 입력이 비었으면 0
     */
    public int estimateTokens(String text) {
        if (StringUtils.isBlank(text)) {
            return 0;
        }
        int cjk = 0;
        int others = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                others++;
            }
        }
        return cjk + (int) Math.ceil(others / 4.0);
    }

    /**
     * 한글/한자/가나 등 문자당 토큰 비용이 높은 문자인지 판정한다.
     */
    private static boolean isCjk(char c) {
        return (c >= 0xAC00 && c <= 0xD7A3)     // 한글 음절
                || (c >= 0x1100 && c <= 0x11FF) // 한글 자모
                || (c >= 0x3130 && c <= 0x318F) // 한글 호환 자모
                || (c >= 0x4E00 && c <= 0x9FFF) // CJK 한자
                || (c >= 0x3040 && c <= 0x30FF) // 히라가나/가타카나
                || (c >= 0xFF00 && c <= 0xFFEF); // 전각 기호
    }
}
