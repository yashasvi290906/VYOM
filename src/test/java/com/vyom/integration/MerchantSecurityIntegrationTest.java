package com.vyom.integration;

import com.vyom.dto.request.AddMerchantMemberRequest;
import com.vyom.dto.request.CreateMerchantRequest;
import com.vyom.dto.request.RejectMerchantRequest;
import com.vyom.dto.request.SuspendMerchantRequest;
import com.vyom.dto.request.UpdateMerchantProfileRequest;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.MerchantMemberResponse;
import com.vyom.dto.response.MerchantResponse;
import com.vyom.entity.User;
import com.vyom.entity.enums.AccountStatus;
import com.vyom.entity.enums.BusinessCategory;
import com.vyom.entity.enums.BusinessType;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 1: Merchant RBAC, Multi-Tenant Isolation & Lifecycle Security Integration Tests")
public class MerchantSecurityIntegrationTest extends BaseIntegrationTest {

    private User alice;
    private User bob;
    private User charlie;

    private String aliceToken;
    private String bobToken;
    private String charlieToken;

    @BeforeEach
    void setupUsers() {
        alice = new User();
        alice.setEmail("alice.merchant@vyom.internal");
        alice.setFullName("Alice Merchant");
        alice.setPasswordHash(passwordEncoder.encode("Password123!"));
        alice.setRole(Role.USER);
        alice = userRepository.save(alice);
        aliceToken = jwtTokenService.generateToken(alice.getId(), alice.getEmail(), alice.getFullName(), Role.USER);

        bob = new User();
        bob.setEmail("bob.user@vyom.internal");
        bob.setFullName("Bob User");
        bob.setPasswordHash(passwordEncoder.encode("Password123!"));
        bob.setRole(Role.USER);
        bob = userRepository.save(bob);
        bobToken = jwtTokenService.generateToken(bob.getId(), bob.getEmail(), bob.getFullName(), Role.USER);

        charlie = new User();
        charlie.setEmail("charlie.viewer@vyom.internal");
        charlie.setFullName("Charlie Viewer");
        charlie.setPasswordHash(passwordEncoder.encode("Password123!"));
        charlie.setRole(Role.USER);
        charlie = userRepository.save(charlie);
        charlieToken = jwtTokenService.generateToken(charlie.getId(), charlie.getEmail(), charlie.getFullName(), Role.USER);
    }

    private MerchantResponse createMerchantAs(String token, String name, boolean submit) {
        CreateMerchantRequest req = new CreateMerchantRequest(
                name,
                name + " Private Limited",
                BusinessType.PRIVATE_LIMITED,
                BusinessCategory.RETAIL,
                "Retail shop",
                name.toLowerCase().replaceAll("\\s+", "") + "@vyom.internal",
                "+919876543210",
                "https://" + name.toLowerCase().replaceAll("\\s+", "") + ".com",
                "IND",
                "100 Commerce St",
                "Bengaluru",
                "Karnataka",
                "560001",
                submit
        );

        HttpHeaders headers = authHeaders(token);
        ResponseEntity<MerchantResponse> res = restTemplate.exchange(
                "/api/v1/merchants",
                HttpMethod.POST,
                new HttpEntity<>(req, headers),
                MerchantResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody();
    }

    private UUID getAliceMembershipId(UUID merchantId) {
        ResponseEntity<List<MerchantMemberResponse>> membersRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantId + "/members",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(aliceToken)),
                new ParameterizedTypeReference<List<MerchantMemberResponse>>() {}
        );
        assertThat(membersRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(membersRes.getBody()).isNotEmpty();
        return membersRes.getBody().stream()
                .filter(m -> m.userId().equals(alice.getId()))
                .findFirst()
                .orElseThrow()
                .membershipId();
    }

    @Test
    @DisplayName("SEC-MCH-01: Unauthenticated requests to merchant endpoints are rejected with 401")
    void unauthenticatedAccess_rejected() {
        ResponseEntity<ErrorResponse> res = restTemplate.exchange(
                "/api/v1/merchants/my",
                HttpMethod.GET,
                new HttpEntity<>(anonymousHeaders()),
                ErrorResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SEC-MCH-02: Multi-tenant isolation - Bob cannot read or access Alice's merchant (404 anti-enumeration)")
    void crossTenantIsolation_denied() {
        MerchantResponse merchant = createMerchantAs(aliceToken, "Alice Shop", false);

        ResponseEntity<ErrorResponse> res = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(bobToken)),
                ErrorResponse.class
        );

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().errorCode()).isEqualTo("MEMBERSHIP_NOT_FOUND");
    }

    @Test
    @DisplayName("SEC-MCH-03: RBAC Privilege Gating - MERCHANT_VIEWER cannot mutate profile (403)")
    void viewerCannotUpdateProfile() {
        MerchantResponse merchant = createMerchantAs(aliceToken, "Alice Store", false);

        // Alice adds Charlie as VIEWER
        AddMerchantMemberRequest addReq = new AddMerchantMemberRequest(charlie.getEmail(), MerchantRole.MERCHANT_VIEWER);
        ResponseEntity<MerchantMemberResponse> addRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.id() + "/members",
                HttpMethod.POST,
                new HttpEntity<>(addReq, authHeaders(aliceToken)),
                MerchantMemberResponse.class
        );
        assertThat(addRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Charlie tries to update profile
        UpdateMerchantProfileRequest updateReq = new UpdateMerchantProfileRequest(
                "Hacked Store Name",
                "Updated desc",
                "+919876543299",
                "https://hacked.com",
                "100 Commerce St",
                "Bengaluru",
                "Karnataka",
                "560001",
                null, null, null
        );
        ResponseEntity<ErrorResponse> updateRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.id() + "/profile",
                HttpMethod.PATCH,
                new HttpEntity<>(updateReq, authHeaders(charlieToken)),
                ErrorResponse.class
        );

        assertThat(updateRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(updateRes.getBody().errorCode()).isEqualTo("INSUFFICIENT_MERCHANT_PRIVILEGE");
    }

    @Test
    @DisplayName("SEC-MCH-04: RBAC Privilege Gating - MERCHANT_OPERATOR cannot add team members (403)")
    void operatorCannotAddMembers() {
        MerchantResponse merchant = createMerchantAs(aliceToken, "Alice Logistics", false);

        // Alice adds Bob as OPERATOR
        AddMerchantMemberRequest addBobReq = new AddMerchantMemberRequest(bob.getEmail(), MerchantRole.MERCHANT_OPERATOR);
        ResponseEntity<MerchantMemberResponse> addBobRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.id() + "/members",
                HttpMethod.POST,
                new HttpEntity<>(addBobReq, authHeaders(aliceToken)),
                MerchantMemberResponse.class
        );
        assertThat(addBobRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Bob (OPERATOR) tries to add Charlie
        AddMerchantMemberRequest addCharlieReq = new AddMerchantMemberRequest(charlie.getEmail(), MerchantRole.MERCHANT_VIEWER);
        ResponseEntity<ErrorResponse> addCharlieRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.id() + "/members",
                HttpMethod.POST,
                new HttpEntity<>(addCharlieReq, authHeaders(bobToken)),
                ErrorResponse.class
        );

        assertThat(addCharlieRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(addCharlieRes.getBody().errorCode()).isEqualTo("INSUFFICIENT_MERCHANT_PRIVILEGE");
    }

    @Test
    @DisplayName("SEC-MCH-05: RBAC Role Hierarchy - MERCHANT_ADMIN cannot revoke or demote MERCHANT_OWNER (403)")
    void adminCannotRevokeOwner() {
        MerchantResponse merchant = createMerchantAs(aliceToken, "Alice Enterprise", false);
        UUID aliceMembershipId = getAliceMembershipId(merchant.id());

        // Alice adds Bob as ADMIN
        AddMerchantMemberRequest addBobReq = new AddMerchantMemberRequest(bob.getEmail(), MerchantRole.MERCHANT_ADMIN);
        ResponseEntity<MerchantMemberResponse> addBobRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.id() + "/members",
                HttpMethod.POST,
                new HttpEntity<>(addBobReq, authHeaders(aliceToken)),
                MerchantMemberResponse.class
        );
        assertThat(addBobRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Bob (ADMIN) tries to revoke Alice (OWNER)
        ResponseEntity<ErrorResponse> revokeRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.id() + "/members/" + aliceMembershipId,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(bobToken)),
                ErrorResponse.class
        );

        assertThat(revokeRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(revokeRes.getBody().errorCode()).isEqualTo("INSUFFICIENT_MERCHANT_PRIVILEGE");
    }

    @Test
    @DisplayName("SEC-MCH-06: Last Active Owner Protection - Sole owner cannot revoke themselves (409)")
    void lastOwnerCannotBeRevoked() {
        MerchantResponse merchant = createMerchantAs(aliceToken, "Alice Solo", false);
        UUID aliceMembershipId = getAliceMembershipId(merchant.id());

        // Alice tries to revoke herself
        ResponseEntity<ErrorResponse> revokeRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.id() + "/members/" + aliceMembershipId,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(aliceToken)),
                ErrorResponse.class
        );

        assertThat(revokeRes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(revokeRes.getBody().errorCode()).isEqualTo("LAST_OWNER_PROTECTION_VIOLATION");
    }

    @Test
    @DisplayName("SEC-MCH-07: Operational Status Gating - Suspended merchant blocks mutating actions (403)")
    void suspendedMerchant_blocksMutations() {
        // Alice creates and submits merchant
        MerchantResponse merchant = createMerchantAs(aliceToken, "Alice Risk Corp", true);

        // System Admin approves merchant
        ResponseEntity<MerchantResponse> approveRes = restTemplate.exchange(
                "/api/v1/admin/merchants/" + merchant.id() + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(adminAuthHeaders()),
                MerchantResponse.class
        );
        assertThat(approveRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approveRes.getBody().accountStatus()).isEqualTo(AccountStatus.ACTIVE);

        // System Admin suspends merchant
        SuspendMerchantRequest suspendReq = new SuspendMerchantRequest("High chargeback volume detected.");
        ResponseEntity<MerchantResponse> suspendRes = restTemplate.exchange(
                "/api/v1/admin/merchants/" + merchant.id() + "/suspend",
                HttpMethod.POST,
                new HttpEntity<>(suspendReq, adminAuthHeaders()),
                MerchantResponse.class
        );
        assertThat(suspendRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suspendRes.getBody().accountStatus()).isEqualTo(AccountStatus.SUSPENDED);

        // Alice tries to update profile of suspended merchant
        UpdateMerchantProfileRequest updateReq = new UpdateMerchantProfileRequest(
                "Attempted Update",
                "New desc",
                "+919876543299",
                "https://attempt.com",
                "100 Commerce St",
                "Bengaluru",
                "Karnataka",
                "560001",
                null, null, null
        );
        ResponseEntity<ErrorResponse> updateRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.id() + "/profile",
                HttpMethod.PATCH,
                new HttpEntity<>(updateReq, authHeaders(aliceToken)),
                ErrorResponse.class
        );

        assertThat(updateRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(updateRes.getBody().errorCode()).isEqualTo("MERCHANT_ACCOUNT_SUSPENDED");
    }

    @Test
    @DisplayName("SEC-MCH-08: Administrative Gating - Non-admins cannot access admin review endpoints (403)")
    void adminEndpoints_requireRoleAdmin() {
        // Alice (regular user) tries to list admin merchants
        ResponseEntity<ErrorResponse> userRes = restTemplate.exchange(
                "/api/v1/admin/merchants",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(aliceToken)),
                ErrorResponse.class
        );
        assertThat(userRes.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Admin accesses admin merchants
        ResponseEntity<String> adminRes = restTemplate.exchange(
                "/api/v1/admin/merchants",
                HttpMethod.GET,
                new HttpEntity<>(adminAuthHeaders()),
                String.class
        );
        assertThat(adminRes.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
