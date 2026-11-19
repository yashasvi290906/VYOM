package com.vyom.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyom.dto.request.CreateWalletRequest;
import com.vyom.dto.request.FundWalletRequest;
import com.vyom.dto.response.FundingResponse;
import com.vyom.dto.response.WalletResponse;
import com.vyom.entity.enums.FundingStatus;
import com.vyom.repository.UserRepository;
import com.vyom.security.JwtTokenService;
import com.vyom.service.FundingService;
import com.vyom.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WalletController.class)
@AutoConfigureMockMvc(addFilters = false)
public class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WalletService walletService;

    @MockBean
    private FundingService fundingService;

    @MockBean
    private JwtTokenService jwtTokenService;

    @MockBean
    private UserRepository userRepository;

    @Test
    @DisplayName("POST /api/v1/wallets should return 201 Created on valid input")
    void shouldReturn201OnValidCreateWallet() throws Exception {
        UUID walletId = UUID.randomUUID();
        WalletResponse response = new WalletResponse(
                walletId, "Alice", "INR", BigDecimal.ZERO.setScale(4), Instant.now(), Instant.now());
        when(walletService.createWallet(any(CreateWalletRequest.class))).thenReturn(response);

        CreateWalletRequest request = new CreateWalletRequest("Alice", "INR");

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.walletId").value(walletId.toString()))
                .andExpect(jsonPath("$.ownerName").value("Alice"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.balance").value("0.0000"));
    }

    @Test
    @DisplayName("POST /api/v1/wallets should return 400 Bad Request on blank ownerName")
    void shouldReturn400OnBlankOwnerName() throws Exception {
        CreateWalletRequest request = new CreateWalletRequest("   ", "INR");

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /api/v1/wallets/{walletId} should return 200 OK")
    void shouldReturn200OnGetWallet() throws Exception {
        UUID walletId = UUID.randomUUID();
        WalletResponse response = new WalletResponse(
                walletId, "Bob", "USD", new BigDecimal("500.0000"), Instant.now(), Instant.now());
        when(walletService.getWallet(walletId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/wallets/" + walletId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(walletId.toString()))
                .andExpect(jsonPath("$.balance").value("500.0000"));
    }

    @Test
    @DisplayName("POST /api/v1/wallets/{walletId}/fundings should return 200 OK on valid funding")
    void shouldReturn200OnValidFunding() throws Exception {
        UUID walletId = UUID.randomUUID();
        FundingResponse response = new FundingResponse(
                UUID.randomUUID(), walletId, new BigDecimal("1000.0000"), "INR", "Demo top-up",
                FundingStatus.COMPLETED, new BigDecimal("1000.0000"), Instant.now());

        when(fundingService.fundWallet(eq("fund-key-1"), eq(walletId), any(FundWalletRequest.class)))
                .thenReturn(response);

        FundWalletRequest request = new FundWalletRequest(new BigDecimal("1000.00"), "Demo top-up");

        mockMvc.perform(post("/api/v1/wallets/" + walletId + "/fundings")
                        .header("Idempotency-Key", "fund-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(walletId.toString()))
                .andExpect(jsonPath("$.amount").value("1000.0000"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.balanceAfter").value("1000.0000"));
    }

    @Test
    @DisplayName("POST /api/v1/wallets/{walletId}/fundings should return 400 Bad Request when Idempotency-Key is missing")
    void shouldReturn400WhenFundingIdempotencyKeyMissing() throws Exception {
        UUID walletId = UUID.randomUUID();
        FundWalletRequest request = new FundWalletRequest(new BigDecimal("1000.00"), "Demo top-up");

        mockMvc.perform(post("/api/v1/wallets/" + walletId + "/fundings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TRANSFER"));
    }
}

