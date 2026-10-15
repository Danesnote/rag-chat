package com.ragchat.util;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpUtilsTests {

    @Test
    void createsConfiguredHttpClient() {
        HttpUtils httpUtils = new HttpUtils(1_000, 3_000);

        assertThat(httpUtils.getClient().version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(httpUtils.getClient().connectTimeout()).contains(Duration.ofSeconds(1));
    }

    @Test
    void createsJsonPostRequestWithHeadersAndTimeout() {
        HttpUtils httpUtils = new HttpUtils(1_000, 3_000);

        HttpRequest request = httpUtils.postRequest(
                "https://example.com/api",
                Map.of("Authorization", "Bearer token"),
                Map.of("message", "hello")
        );

        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo("https://example.com/api");
        assertThat(request.timeout()).contains(Duration.ofSeconds(3));
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer token");
        assertThat(request.bodyPublisher()).isPresent();
    }

    @Test
    void rejectsNonHttpUrl() {
        HttpUtils httpUtils = new HttpUtils(1_000, 3_000);

        assertThatThrownBy(() -> httpUtils.getRequest("file:///tmp/data.json", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP/HTTPS");
    }
}
