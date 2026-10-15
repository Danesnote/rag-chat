package com.ragchat.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * JSON 요청과 스트리밍 요청을 지원하는 JDK {@link HttpClient} 기반 유틸리티입니다.
 */
public class HttpUtils {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    /**
     * 기본 {@link ObjectMapper}를 사용하여 HTTP 유틸리티를 생성합니다.
     *
     * @param connectionTimeout 서버 연결 제한 시간(밀리초). 0보다 커야 합니다.
     * @param readTimeout 일반 요청의 전체 제한 시간(밀리초). 0이면 제한 시간을 적용하지 않습니다.
     *
     * @throws IllegalArgumentException 연결 제한 시간이 0 이하이거나 요청 제한 시간이 음수인 경우
     */
    public HttpUtils(int connectionTimeout, int readTimeout) {
        this(connectionTimeout, readTimeout, new ObjectMapper().findAndRegisterModules());
    }

    /**
     * 지정한 {@link ObjectMapper}를 사용하여 HTTP 유틸리티를 생성합니다.
     *
     * @param connectionTimeout 서버 연결 제한 시간(밀리초). 0보다 커야 합니다.
     * @param readTimeout 일반 요청의 전체 제한 시간(밀리초). 0이면 제한 시간을 적용하지 않습니다.
     * @param objectMapper 요청 객체 직렬화와 응답 본문 역직렬화에 사용할 ObjectMapper
     *
     * @throws IllegalArgumentException 연결 제한 시간이 0 이하이거나 요청 제한 시간이 음수인 경우
     * @throws NullPointerException objectMapper가 null인 경우
     */
    public HttpUtils(int connectionTimeout, int readTimeout, ObjectMapper objectMapper) {
        if (connectionTimeout <= 0) {
            throw new IllegalArgumentException("connectionTimeout must be greater than 0");
        }
        if (readTimeout < 0) {
            throw new IllegalArgumentException("readTimeout must not be negative");
        }

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectionTimeout))
                .build();
        this.requestTimeout = readTimeout == 0 ? null : Duration.ofMillis(readTimeout);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 이 유틸리티가 공통으로 사용하는 HTTP 클라이언트를 반환합니다.
     *
     * @return 설정된 공용 HttpClient 인스턴스
     */
    public HttpClient getClient() {
        return httpClient;
    }

    /**
     * GET 요청을 전송하고 JSON 응답을 지정한 타입으로 변환합니다.
     *
     * @param url 요청을 전송할 HTTP 또는 HTTPS URL
     * @param headers 요청 헤더. 헤더가 없으면 null을 사용할 수 있습니다.
     * @param responseType 응답 본문을 변환할 클래스
     * @param <T> 응답 타입
     * @return 지정한 타입으로 변환된 응답 본문
     * @throws IllegalStateException 응답 상태가 2xx가 아니거나 응답 변환에 실패한 경우
     * @throws UncheckedIOException HTTP 통신 중 입출력 오류가 발생한 경우
     */
    public <T> T get(String url, Map<String, String> headers, Class<T> responseType) {
        HttpResponse<String> response = send(
                getRequest(url, headers),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        return readResponse(response, responseType);
    }

    /**
     * 요청 객체를 JSON으로 직렬화하여 POST 요청을 전송하고 응답을 지정한 타입으로 변환합니다.
     *
     * @param url 요청을 전송할 HTTP 또는 HTTPS URL
     * @param headers 요청 헤더. 헤더가 없으면 null을 사용할 수 있습니다.
     * @param body JSON으로 직렬화할 요청 본문. 문자열은 별도 직렬화 없이 그대로 전송합니다.
     * @param responseType 응답 본문을 변환할 클래스
     * @param <T> 응답 타입
     * @return 지정한 타입으로 변환된 응답 본문
     *
     * @throws IllegalArgumentException 요청 본문 직렬화에 실패한 경우
     * @throws IllegalStateException 응답 상태가 2xx가 아니거나 응답 변환에 실패한 경우
     * @throws UncheckedIOException HTTP 통신 중 입출력 오류가 발생한 경우
     */
    public <T> T post(
            String url,
            Map<String, String> headers,
            Object body,
            Class<T> responseType
    ) {
        HttpResponse<String> response = send(
                postRequest(url, headers, body),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        return readResponse(response, responseType);
    }

    /**
     * 요청 객체를 JSON으로 직렬화하여 POST 요청을 전송하고 제네릭 타입의 응답으로 변환합니다.
     *
     * @param url 요청을 전송할 HTTP 또는 HTTPS URL
     * @param headers 요청 헤더. 헤더가 없으면 null을 사용할 수 있습니다.
     * @param body JSON으로 직렬화할 요청 본문
     * @param responseType 응답 본문의 제네릭 타입 정보
     * @param <T> 응답 타입
     * @return 지정한 타입으로 변환된 응답 본문
     */
    public <T> T post(
            String url,
            Map<String, String> headers,
            Object body,
            TypeReference<T> responseType
    ) {
        HttpResponse<String> response = send(
                postRequest(url, headers, body),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        return readResponse(response, responseType);
    }

    /**
     * SSE 등 장시간 연결을 유지하는 요청입니다. 반환된 응답 본문은 호출자가 닫아야 합니다.
     * 스트리밍 연결에는 일반 요청 제한 시간을 적용하지 않습니다.
     *
     * @param url 요청을 전송할 HTTP 또는 HTTPS URL
     * @param headers 요청 헤더. 헤더가 없으면 null을 사용할 수 있습니다.
     * @param body JSON으로 직렬화할 요청 본문. 문자열은 별도 직렬화 없이 그대로 전송합니다.
     * @return 스트리밍 응답 본문을 포함하는 HTTP 응답
     *
     * @throws IllegalArgumentException 요청 본문 직렬화에 실패한 경우
     * @throws UncheckedIOException HTTP 통신 중 입출력 오류가 발생한 경우
     */
    public HttpResponse<InputStream> postStream(
            String url,
            Map<String, String> headers,
            Object body
    ) {
        return send(
                postRequest(url, headers, body, false),
                HttpResponse.BodyHandlers.ofInputStream()
        );
    }

    /**
     * GET 요청에 사용할 {@link HttpRequest}를 생성합니다.
     *
     * @param url 요청을 전송할 HTTP 또는 HTTPS URL
     * @param headers 요청 헤더. 헤더가 없으면 null을 사용할 수 있습니다.
     * @return GET 방식으로 구성된 HTTP 요청
     * @throws IllegalArgumentException URL이 HTTP 또는 HTTPS 형식이 아닌 경우
     */
    public HttpRequest getRequest(String url, Map<String, String> headers) {
        return addHeaders(requestBuilder(url, true), headers)
                .GET()
                .build();
    }

    /**
     * JSON POST 요청에 사용할 {@link HttpRequest}를 생성합니다.
     *
     * @param url 요청을 전송할 HTTP 또는 HTTPS URL
     * @param headers 요청 헤더. 헤더가 없으면 null을 사용할 수 있습니다.
     * @param body JSON으로 직렬화할 요청 본문. 문자열은 별도 직렬화 없이 그대로 사용합니다.
     * @return POST 방식으로 구성된 HTTP 요청
     * @throws IllegalArgumentException URL이 올바르지 않거나 요청 본문 직렬화에 실패한 경우
     */
    public HttpRequest postRequest(String url, Map<String, String> headers, Object body) {
        return postRequest(url, headers, body, true);
    }

    /**
     * 구성된 HTTP 요청을 동기 방식으로 전송합니다.
     *
     * @param request 전송할 HTTP 요청
     * @param responseBodyHandler 응답 본문 변환 방식
     * @param <T> 변환된 응답 본문의 타입
     * @return 상태 코드, 헤더 및 변환된 본문을 포함한 HTTP 응답
     * @throws IllegalStateException 요청 처리 스레드가 중단된 경우
     * @throws UncheckedIOException HTTP 통신 중 입출력 오류가 발생한 경우
     */
    public <T> HttpResponse<T> send(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler
    ) {
        try {
            return httpClient.send(request, responseBodyHandler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request was interrupted", e);
        } catch (IOException e) {
            throw new UncheckedIOException("HTTP request failed", e);
        }
    }

    /**
     * 구성된 HTTP 요청을 비동기 방식으로 전송합니다.
     *
     * @param request 전송할 HTTP 요청
     * @param responseBodyHandler 응답 본문 변환 방식
     * @param <T> 변환된 응답 본문의 타입
     * @return HTTP 응답을 비동기로 제공하는 CompletableFuture
     */
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler
    ) {
        return httpClient.sendAsync(request, responseBodyHandler);
    }

    /**
     * JSON POST 요청을 생성하고 필요에 따라 일반 요청 제한 시간을 적용합니다.
     *
     * @param url 요청을 전송할 HTTP 또는 HTTPS URL
     * @param headers 요청 헤더. 헤더가 없으면 null을 사용할 수 있습니다.
     * @param body JSON으로 직렬화할 요청 본문
     * @param applyRequestTimeout true이면 생성자에서 설정한 일반 요청 제한 시간을 적용합니다.
     * @return POST 방식으로 구성된 HTTP 요청
     */
    private HttpRequest postRequest(
            String url,
            Map<String, String> headers,
            Object body,
            boolean applyRequestTimeout
    ) {
        return addHeaders(requestBuilder(url, applyRequestTimeout), headers)
                .setHeader("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
                .build();
    }

    /**
     * URL을 기반으로 HTTP 요청 빌더를 생성합니다.
     *
     * @param url 요청을 전송할 HTTP 또는 HTTPS URL
     * @param applyRequestTimeout true이면 생성자에서 설정한 일반 요청 제한 시간을 적용합니다.
     * @return URI와 제한 시간이 설정된 HTTP 요청 빌더
     */
    private HttpRequest.Builder requestBuilder(String url, boolean applyRequestTimeout) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(toHttpUri(url));
        if (applyRequestTimeout && requestTimeout != null) {
            requestBuilder.timeout(requestTimeout);
        }
        return requestBuilder;
    }

    /**
     * 요청 빌더에 사용자 정의 헤더를 설정합니다.
     *
     * @param requestBuilder 헤더를 추가할 요청 빌더
     * @param headers 추가할 헤더. null이면 아무 헤더도 추가하지 않습니다.
     * @return 헤더가 반영된 요청 빌더
     * @throws NullPointerException 헤더 이름 또는 값이 null인 경우
     */
    private HttpRequest.Builder addHeaders(
            HttpRequest.Builder requestBuilder,
            Map<String, String> headers
    ) {
        if (headers != null) {
            headers.forEach((name, value) -> requestBuilder.setHeader(
                    Objects.requireNonNull(name, "header name must not be null"),
                    Objects.requireNonNull(value, "header value must not be null")));
        }
        return requestBuilder;
    }

    /**
     * 요청 본문 객체를 JSON 문자열로 변환합니다.
     *
     * @param body JSON으로 변환할 요청 본문. 문자열은 그대로 반환합니다.
     * @return HTTP 요청에 사용할 JSON 문자열
     * @throws NullPointerException 요청 본문이 null인 경우
     * @throws IllegalArgumentException 요청 본문 직렬화에 실패한 경우
     */
    private String toJson(Object body) {
        Objects.requireNonNull(body, "body must not be null");
        if (body instanceof String stringBody) {
            return stringBody;
        }

        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize HTTP request body", e);
        }
    }

    /**
     * HTTP 응답 상태를 검증하고 응답 본문을 지정한 타입으로 변환합니다.
     *
     * @param response 문자열 본문을 포함하는 HTTP 응답
     * @param responseType 응답 본문을 변환할 클래스
     * @param <T> 변환할 응답 타입
     * @return 지정한 타입으로 변환된 응답 본문. responseType이 Void이면 null입니다.
     *
     * @throws IllegalStateException 응답 상태가 2xx가 아니거나 응답 변환에 실패한 경우
     */
    private <T> T readResponse(HttpResponse<String> response, Class<T> responseType) {
        validateResponse(response);
        if (responseType == String.class) {
            return responseType.cast(response.body());
        }
        if (responseType == Void.class) {
            return null;
        }

        try {
            return objectMapper.readValue(response.body(), responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize HTTP response body", e);
        }
    }

    private <T> T readResponse(HttpResponse<String> response, TypeReference<T> responseType) {
        validateResponse(response);

        try {
            return objectMapper.readValue(response.body(), responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize HTTP response body", e);
        }
    }

    private void validateResponse(HttpResponse<?> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "HTTP request failed with status " + response.statusCode() + ": " + response.body()
            );
        }
    }

    /**
     * 문자열 URL을 HTTP 또는 HTTPS URI로 변환합니다.
     *
     * @param url 변환할 URL 문자열
     * @return 검증된 HTTP 또는 HTTPS URI
     *
     * @throws NullPointerException URL이 null인 경우
     * @throws IllegalArgumentException URL 형식이 올바르지 않거나 HTTP/HTTPS가 아닌 경우
     */
    private URI toHttpUri(String url) {
        URI uri = URI.create(Objects.requireNonNull(url, "url must not be null"));
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only HTTP/HTTPS URLs are supported: " + url);
        }
        return uri;
    }
}
