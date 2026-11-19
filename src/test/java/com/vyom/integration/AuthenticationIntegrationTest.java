package com.vyom.integration;

import com.vyom.dto.request.LoginRequest;
import com.vyom.dto.request.RegisterRequest;
import com.vyom.dto.response.AuthResponse;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.UserResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

public class AuthenticationIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Should successfully register a new customer and return JWT token")
    void shouldRegisterNewCustomer() {
        RegisterRequest request = new RegisterRequest("alice@example.com", "SecurePass123!", "Alice Wonder");

        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new HttpEntity<>(request, anonymousHeaders()),
                AuthResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().userId()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo("alice@example.com");
        assertThat(response.getBody().fullName()).isEqualTo("Alice Wonder");
    }

    @Test
    @DisplayName("Should reject duplicate registration with 409 Conflict")
    void shouldRejectDuplicateRegistration() {
        RegisterRequest request = new RegisterRequest("dup@example.com", "SecurePass123!", "Duplicate User");

        ResponseEntity<AuthResponse> first = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new HttpEntity<>(request, anonymousHeaders()),
                AuthResponse.class
        );
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<ErrorResponse> second = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new HttpEntity<>(request, anonymousHeaders()),
                ErrorResponse.class
        );
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().errorCode()).isEqualTo("DUPLICATE_IDENTITY");
    }

    @Test
    @DisplayName("Should reject registration with invalid email or weak password")
    void shouldRejectInvalidRegistrationInputs() {
        RegisterRequest invalidEmail = new RegisterRequest("not-an-email", "SecurePass123!", "Alice");
        ResponseEntity<ErrorResponse> res1 = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new HttpEntity<>(invalidEmail, anonymousHeaders()),
                ErrorResponse.class
        );
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        RegisterRequest shortPass = new RegisterRequest("valid@example.com", "short", "Alice");
        ResponseEntity<ErrorResponse> res2 = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new HttpEntity<>(shortPass, anonymousHeaders()),
                ErrorResponse.class
        );
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should successfully authenticate with valid credentials")
    void shouldLoginWithValidCredentials() {
        RegisterRequest registerReq = new RegisterRequest("login-user@example.com", "Password1234!", "Login User");
        restTemplate.postForEntity("/api/v1/auth/register", new HttpEntity<>(registerReq, anonymousHeaders()), AuthResponse.class);

        LoginRequest loginReq = new LoginRequest("login-user@example.com", "Password1234!");
        ResponseEntity<AuthResponse> loginRes = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(loginReq, anonymousHeaders()),
                AuthResponse.class
        );

        assertThat(loginRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginRes.getBody()).isNotNull();
        assertThat(loginRes.getBody().token()).isNotBlank();
        assertThat(loginRes.getBody().email()).isEqualTo("login-user@example.com");
    }

    @Test
    @DisplayName("Should reject login with invalid password with 401 Unauthorized")
    void shouldRejectInvalidPassword() {
        RegisterRequest registerReq = new RegisterRequest("wrong-pass@example.com", "CorrectPassword123!", "User");
        restTemplate.postForEntity("/api/v1/auth/register", new HttpEntity<>(registerReq, anonymousHeaders()), AuthResponse.class);

        LoginRequest loginReq = new LoginRequest("wrong-pass@example.com", "IncorrectPassword!");
        ResponseEntity<ErrorResponse> loginRes = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(loginReq, anonymousHeaders()),
                ErrorResponse.class
        );

        assertThat(loginRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should return authenticated user profile from GET /api/v1/auth/me")
    void shouldReturnUserProfileForAuthenticatedRequest() {
        RegisterRequest registerReq = new RegisterRequest("profile@example.com", "Pass123456!", "Profile Test");
        ResponseEntity<AuthResponse> regRes = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new HttpEntity<>(registerReq, anonymousHeaders()),
                AuthResponse.class
        );
        String token = regRes.getBody().token();

        HttpHeaders headers = authHeaders(token);
        ResponseEntity<UserResponse> meRes = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                UserResponse.class
        );

        assertThat(meRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(meRes.getBody()).isNotNull();
        assertThat(meRes.getBody().email()).isEqualTo("profile@example.com");
        assertThat(meRes.getBody().fullName()).isEqualTo("Profile Test");
    }

    @Test
    @DisplayName("Should reject unauthenticated request to /api/v1/auth/me with 401")
    void shouldRejectUnauthenticatedMeRequest() {
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(anonymousHeaders()),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should reject malformed or tampered JWT token with 401")
    void shouldRejectMalformedToken() {
        HttpHeaders headers = authHeaders("malformed.jwt.token");
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                ErrorResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
