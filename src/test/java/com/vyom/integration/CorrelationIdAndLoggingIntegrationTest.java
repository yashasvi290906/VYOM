package com.vyom.integration;

import com.vyom.dto.response.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdAndLoggingIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should propagate custom X-Correlation-ID header in response")
    void testPropagateCustomCorrelationId() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-ID", "custom-trace-12345");
        headers.set("Authorization", "Bearer " + defaultAuthToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("custom-trace-12345");
    }

    @Test
    @DisplayName("Should generate X-Correlation-ID header when not provided by client")
    void testGenerateCorrelationIdWhenMissing() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + defaultAuthToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String correlationId = response.getHeaders().getFirst("X-Correlation-ID");
        assertThat(correlationId).isNotNull();
        assertThat(correlationId).startsWith("corr-");
    }

    @Test
    @DisplayName("SEC-P3-01: Should reject oversized correlation ID and generate safe fallback")
    void testRejectOversizedCorrelationId() {
        String oversized = "a".repeat(128);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-ID", oversized);
        headers.set("Authorization", "Bearer " + defaultAuthToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String returnedId = response.getHeaders().getFirst("X-Correlation-ID");
        assertThat(returnedId).isNotNull();
        assertThat(returnedId).isNotEqualTo(oversized);
        assertThat(returnedId).startsWith("corr-");
        assertThat(returnedId.length()).isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("SEC-P3-01: Should reject CRLF injection in correlation ID and generate safe fallback")
    void testRejectCrlfInjectionCorrelationId() throws Exception {
        String crlfPayload = "trace-123\r\nInjected-Header: evil-value";
        assertThat(com.vyom.security.CorrelationIdFilter.isValidCorrelationId(crlfPayload)).isFalse();

        // Also test filter execution directly with MockHttpServletRequest
        com.vyom.security.CorrelationIdFilter filter = new com.vyom.security.CorrelationIdFilter();
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", crlfPayload);
        org.springframework.mock.web.MockHttpServletResponse response = new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.mock.web.MockFilterChain filterChain = new org.springframework.mock.web.MockFilterChain();

        filter.doFilter(request, response, filterChain);

        String returnedId = response.getHeader("X-Correlation-ID");
        assertThat(returnedId).isNotNull();
        assertThat(returnedId).doesNotContain("\r");
        assertThat(returnedId).doesNotContain("\n");
        assertThat(returnedId).doesNotContain("Injected-Header");
        assertThat(returnedId).startsWith("corr-");
        assertThat(returnedId.length()).isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("SEC-P3-01: Should reject malformed special characters in correlation ID")
    void testRejectMalformedCharsCorrelationId() {
        String malformed = "trace<script>alert(1)</script>";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-ID", malformed);
        headers.set("Authorization", "Bearer " + defaultAuthToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String returnedId = response.getHeaders().getFirst("X-Correlation-ID");
        assertThat(returnedId).isNotNull();
        assertThat(returnedId).doesNotContain("<script>");
        assertThat(returnedId).startsWith("corr-");
    }

    @Test
    @DisplayName("Should include correlationId in 401 Unauthorized error response and header")
    void testCorrelationIdIn401ErrorResponse() {
        HttpHeaders headers = anonymousHeaders();
        headers.set("X-Correlation-ID", "unauth-trace-999");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/wallets",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("unauth-trace-999");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().correlationId()).isEqualTo("unauth-trace-999");
    }

    @Test
    @DisplayName("Should include correlationId in 404 Not Found error response and header")
    void testCorrelationIdIn404ErrorResponse() {
        HttpHeaders headers = authHeaders(defaultAuthToken);
        headers.set("X-Correlation-ID", "notfound-trace-777");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/wallets/00000000-0000-0000-0000-000000000001",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("notfound-trace-777");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().correlationId()).isEqualTo("notfound-trace-777");
    }
}
