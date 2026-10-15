package com.ragchat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragchat.util.HttpUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpUtilsConfig {

    /**
     * 애플리케이션에서 공통으로 사용할 HttpUtils Bean을 생성합니다.
     *
     * @param objectMapper Spring Jackson 설정이 적용된 ObjectMapper
     * @return 연결 제한 시간 20초, 일반 요청 제한 시간 30초가 설정된 HttpUtils
     */
    @Bean
    public HttpUtils httpUtils(ObjectMapper objectMapper) {
        return new HttpUtils(20_000, 30_000, objectMapper);
    }
}
