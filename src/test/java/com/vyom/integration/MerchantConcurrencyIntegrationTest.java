package com.vyom.integration;

import com.vyom.dto.request.AddMerchantMemberRequest;
import com.vyom.dto.request.CreateMerchantRequest;
import com.vyom.dto.request.RejectMerchantRequest;
import com.vyom.dto.request.UpdateMerchantMemberRoleRequest;
import com.vyom.dto.response.ErrorResponse;
import com.vyom.dto.response.MerchantMemberResponse;
import com.vyom.dto.response.MerchantResponse;
import com.vyom.entity.User;
import com.vyom.entity.enums.BusinessCategory;
import com.vyom.entity.enums.BusinessType;
import com.vyom.entity.enums.MerchantRole;
import com.vyom.entity.enums.OnboardingStatus;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 1.1: Merchant Concurrency, Last-Owner Protection & Race Condition Verification")
public class MerchantConcurrencyIntegrationTest extends BaseIntegrationTest {

    private User owner1;
    private User owner2;
    private User employee;

    private String owner1Token;
    private String owner2Token;

    @BeforeEach
    void setupUsers() {
        owner1 = new User();
        owner1.setEmail("owner1.conc@vyom.internal");
        owner1.setFullName("Owner One");
        owner1.setPasswordHash(passwordEncoder.encode("Password123!"));
        owner1.setRole(Role.USER);
        owner1 = userRepository.save(owner1);
        owner1Token = jwtTokenService.generateToken(owner1.getId(), owner1.getEmail(), owner1.getFullName(), Role.USER);

        owner2 = new User();
        owner2.setEmail("owner2.conc@vyom.internal");
        owner2.setFullName("Owner Two");
        owner2.setPasswordHash(passwordEncoder.encode("Password123!"));
        owner2.setRole(Role.USER);
        owner2 = userRepository.save(owner2);
        owner2Token = jwtTokenService.generateToken(owner2.getId(), owner2.getEmail(), owner2.getFullName(), Role.USER);

        employee = new User();
        employee.setEmail("emp.conc@vyom.internal");
        employee.setFullName("Employee One");
        employee.setPasswordHash(passwordEncoder.encode("Password123!"));
        employee.setRole(Role.USER);
        employee = userRepository.save(employee);
    }

    private MerchantResponse createMerchantWithTwoOwners() {
        CreateMerchantRequest req = new CreateMerchantRequest(
                "Conc Merchant " + UUID.randomUUID().toString().substring(0, 6),
                "Conc Merchant Legal Ltd",
                BusinessType.PRIVATE_LIMITED,
                BusinessCategory.RETAIL,
                "Retail shop",
                "conc" + UUID.randomUUID().toString().substring(0, 6) + "@vyom.internal",
                "+919876543210",
                "https://conc.com",
                "IND",
                "100 Commerce St",
                "Bengaluru",
                "Karnataka",
                "560001",
                false
        );

        HttpHeaders headers = authHeaders(owner1Token);
        ResponseEntity<MerchantResponse> res = restTemplate.exchange(
                "/api/v1/merchants",
                HttpMethod.POST,
                new HttpEntity<>(req, headers),
                MerchantResponse.class
        );
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MerchantResponse merchant = res.getBody();

        // Add owner2 as second MERCHANT_OWNER
        AddMerchantMemberRequest addReq = new AddMerchantMemberRequest(owner2.getEmail(), MerchantRole.MERCHANT_OWNER);
        ResponseEntity<MerchantMemberResponse> addRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.id() + "/members",
                HttpMethod.POST,
                new HttpEntity<>(addReq, authHeaders(owner1Token)),
                MerchantMemberResponse.class
        );
        assertThat(addRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        return merchant;
    }

    private UUID getMembershipId(UUID merchantId, UUID userId, String token) {
        ResponseEntity<List<MerchantMemberResponse>> membersRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchantId + "/members",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                new ParameterizedTypeReference<List<MerchantMemberResponse>>() {}
        );
        return membersRes.getBody().stream()
                .filter(m -> m.userId().equals(userId))
                .findFirst()
                .orElseThrow()
                .membershipId();
    }

    @Test
    @DisplayName("CONC-MCH-01: Simultaneous demotion of two owners: Exactly one succeeds, second fails with 409 LastOwnerProtection")
    void concurrentOwnerDemotion_guaranteesAtLeastOneActiveOwner() throws InterruptedException, ExecutionException {
        MerchantResponse merchant = createMerchantWithTwoOwners();
        UUID owner1MemId = getMembershipId(merchant.id(), owner1.getId(), owner1Token);
        UUID owner2MemId = getMembershipId(merchant.id(), owner2.getId(), owner2Token);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        // Task 1: Owner2 demotes Owner1 to MERCHANT_ADMIN
        Callable<Integer> task1 = () -> {
            readyLatch.countDown();
            startLatch.await();
            UpdateMerchantMemberRoleRequest req = new UpdateMerchantMemberRoleRequest(MerchantRole.MERCHANT_ADMIN);
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/v1/merchants/" + merchant.id() + "/members/" + owner1MemId,
                    HttpMethod.PATCH,
                    new HttpEntity<>(req, authHeaders(owner2Token)),
                    String.class
            );
            return res.getStatusCode().value();
        };

        // Task 2: Owner1 demotes Owner2 to MERCHANT_ADMIN
        Callable<Integer> task2 = () -> {
            readyLatch.countDown();
            startLatch.await();
            UpdateMerchantMemberRoleRequest req = new UpdateMerchantMemberRoleRequest(MerchantRole.MERCHANT_ADMIN);
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/v1/merchants/" + merchant.id() + "/members/" + owner2MemId,
                    HttpMethod.PATCH,
                    new HttpEntity<>(req, authHeaders(owner1Token)),
                    String.class
            );
            return res.getStatusCode().value();
        };

        Future<Integer> f1 = executor.submit(task1);
        Future<Integer> f2 = executor.submit(task2);

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Fire simultaneously

        int status1 = f1.get();
        int status2 = f2.get();
        executor.shutdown();

        // Exactly one must succeed (200 OK), and exactly one must fail with 409 or 403
        List<Integer> statuses = List.of(status1, status2);
        assertThat(statuses).contains(200);
        assertThat(statuses).containsAnyOf(409, 403);

        // Direct database verification: exactly 1 active owner remains
        Integer activeOwners = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM merchant_members WHERE merchant_id = ? AND merchant_role = 'MERCHANT_OWNER' AND membership_status = 'ACTIVE'",
                Integer.class, merchant.id());
        assertThat(activeOwners).isEqualTo(1);
    }

    @Test
    @DisplayName("CONC-MCH-02: Simultaneous revocation of two owners: Exactly one succeeds, second fails with 409")
    void concurrentOwnerRevocation_guaranteesAtLeastOneActiveOwner() throws InterruptedException, ExecutionException {
        MerchantResponse merchant = createMerchantWithTwoOwners();
        UUID owner1MemId = getMembershipId(merchant.id(), owner1.getId(), owner1Token);
        UUID owner2MemId = getMembershipId(merchant.id(), owner2.getId(), owner2Token);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        // Task 1: Owner2 revokes Owner1
        Callable<Integer> task1 = () -> {
            readyLatch.countDown();
            startLatch.await();
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/v1/merchants/" + merchant.id() + "/members/" + owner1MemId,
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(owner2Token)),
                    String.class
            );
            return res.getStatusCode().value();
        };

        // Task 2: Owner1 revokes Owner2
        Callable<Integer> task2 = () -> {
            readyLatch.countDown();
            startLatch.await();
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/v1/merchants/" + merchant.id() + "/members/" + owner2MemId,
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(owner1Token)),
                    String.class
            );
            return res.getStatusCode().value();
        };

        Future<Integer> f1 = executor.submit(task1);
        Future<Integer> f2 = executor.submit(task2);

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int status1 = f1.get();
        int status2 = f2.get();
        executor.shutdown();

        List<Integer> statuses = List.of(status1, status2);
        // One returns 204 No Content, the second returns 409 Conflict or 404 Not Found
        assertThat(statuses).contains(204);
        assertThat(statuses).containsAnyOf(409, 404);

        // Direct database verification: exactly 1 active owner remains
        Integer activeOwners = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM merchant_members WHERE merchant_id = ? AND merchant_role = 'MERCHANT_OWNER' AND membership_status = 'ACTIVE'",
                Integer.class, merchant.id());
        assertThat(activeOwners).isEqualTo(1);
    }

    @Test
    @DisplayName("CONC-MCH-03: Concurrent Admin Decision: Simultaneous Approve and Reject on submitted merchant")
    void concurrentAdminDecisions_onlyOneSucceeds() throws InterruptedException, ExecutionException {
        // Create and submit a merchant
        CreateMerchantRequest req = new CreateMerchantRequest(
                "Admin Conc Biz " + UUID.randomUUID().toString().substring(0, 6),
                "Admin Conc Legal Ltd",
                BusinessType.PRIVATE_LIMITED,
                BusinessCategory.RETAIL,
                "Retail shop",
                "adminconc" + UUID.randomUUID().toString().substring(0, 6) + "@vyom.internal",
                "+919876543210",
                "https://adminconc.com",
                "IND",
                "100 Commerce St",
                "Bengaluru",
                "Karnataka",
                "560001",
                true // SUBMITTED
        );

        ResponseEntity<MerchantResponse> createRes = restTemplate.exchange(
                "/api/v1/merchants",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeaders(owner1Token)),
                MerchantResponse.class
        );
        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID merchantId = createRes.getBody().id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        // Admin 1: Approve
        Callable<Integer> taskApprove = () -> {
            readyLatch.countDown();
            startLatch.await();
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/v1/admin/merchants/" + merchantId + "/approve",
                    HttpMethod.POST,
                    new HttpEntity<>(adminAuthHeaders()),
                    String.class
            );
            return res.getStatusCode().value();
        };

        // Admin 2: Reject
        Callable<Integer> taskReject = () -> {
            readyLatch.countDown();
            startLatch.await();
            RejectMerchantRequest rejectReq = new RejectMerchantRequest("Non-compliant document.");
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/v1/admin/merchants/" + merchantId + "/reject",
                    HttpMethod.POST,
                    new HttpEntity<>(rejectReq, adminAuthHeaders()),
                    String.class
            );
            return res.getStatusCode().value();
        };

        Future<Integer> fApprove = executor.submit(taskApprove);
        Future<Integer> fReject = executor.submit(taskReject);

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int sApprove = fApprove.get();
        int sReject = fReject.get();
        executor.shutdown();

        // One must succeed (200 OK), other must fail with 409 Conflict (invalid state transition)
        List<Integer> results = List.of(sApprove, sReject);
        assertThat(results).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    @DisplayName("CONC-MCH-04: Concurrent Member Addition: Adding same user simultaneously produces exactly one member and one 409")
    void concurrentAddMember_duplicateIsRejectedSafely() throws InterruptedException, ExecutionException {
        MerchantResponse merchant = createMerchantWithTwoOwners();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<Integer> addTask = () -> {
            readyLatch.countDown();
            startLatch.await();
            AddMerchantMemberRequest req = new AddMerchantMemberRequest(employee.getEmail(), MerchantRole.MERCHANT_OPERATOR);
            ResponseEntity<String> res = restTemplate.exchange(
                    "/api/v1/merchants/" + merchant.id() + "/members",
                    HttpMethod.POST,
                    new HttpEntity<>(req, authHeaders(owner1Token)),
                    String.class
            );
            return res.getStatusCode().value();
        };

        Future<Integer> f1 = executor.submit(addTask);
        Future<Integer> f2 = executor.submit(addTask);

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        int s1 = f1.get();
        int s2 = f2.get();
        executor.shutdown();

        // Exactly one must return 201 Created, and one must return 409 Conflict
        List<Integer> statuses = List.of(s1, s2);
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);

        // Verify only 1 membership exists for employee
        ResponseEntity<List<MerchantMemberResponse>> membersRes = restTemplate.exchange(
                "/api/v1/merchants/" + merchant.id() + "/members",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(owner1Token)),
                new ParameterizedTypeReference<List<MerchantMemberResponse>>() {}
        );
        long count = membersRes.getBody().stream()
                .filter(m -> m.userId().equals(employee.getId()))
                .count();
        assertThat(count).isEqualTo(1L);
    }
}
