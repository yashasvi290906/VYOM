package com.vyom.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorHealthIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Actuator: /actuator/health should return UP status and database component health")
    void testActuatorHealthEndpoint() {
        HttpHeaders headers = anonymousHeaders();
        headers.set("X-Correlation-ID", "health-check-trace-1");

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/actuator/health",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("health-check-trace-1");

        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("status").asText()).isEqualTo("UP");
        assertThat(body.path("components").path("db").path("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("Actuator: Kubernetes liveness probe /actuator/health/liveness should return UP")
    void testLivenessProbe() {
        HttpHeaders headers = anonymousHeaders();

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/actuator/health/liveness",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("Actuator: Kubernetes readiness probe /actuator/health/readiness should return UP")
    void testReadinessProbe() {
        HttpHeaders headers = anonymousHeaders();

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/actuator/health/readiness",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("SEC-P3-05: Unauthenticated access to /actuator/metrics must be rejected with 401")
    void testActuatorMetrics_Unauthenticated_Returns401() {
        HttpHeaders headers = anonymousHeaders();

        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/metrics",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SEC-P3-05: Ordinary customer (USER) access to /actuator/metrics must be rejected with 403")
    void testActuatorMetrics_UserRole_Returns403() {
        HttpHeaders headers = authHeaders(defaultAuthToken);

        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/metrics",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("SEC-P3-05: Authorized ADMIN access to /actuator/metrics is permitted (200)")
    void testActuatorMetrics_AdminRole_Returns200() {
        HttpHeaders headers = adminAuthHeaders();

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/actuator/metrics",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("names")).isNotNull();
    }

    @Test
    @DisplayName("SEC-P3-05: Sensitive /actuator/env must be unexposed (404/401)")
    void testActuatorEnv_Unexposed() {
        HttpHeaders headers = adminAuthHeaders();

        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/env",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        // Not exposed in management.endpoints.web.exposure.include, so Actuator returns 404
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
