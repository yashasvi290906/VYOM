package com.vyom.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyom.dto.request.CreateTransferRequest;
import com.vyom.dto.response.TransferResponse;
import com.vyom.entity.enums.TransferStatus;
import com.vyom.service.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
public class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransferService transferService;

    @Test
    @DisplayName("POST /api/v1/transfers should return 200 OK on successful transfer")
    void shouldReturn200OnSuccessfulTransfer() throws Exception {
        UUID transferId = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID dest = UUID.randomUUID();
        TransferResponse response = new TransferResponse(
                transferId, source, dest, new BigDecimal("100.0000"), "INR", "Rent", TransferStatus.COMPLETED, Instant.now());

        when(transferService.transfer(eq("key-123"), any(CreateTransferRequest.class))).thenReturn(response);

        CreateTransferRequest request = new CreateTransferRequest(source, dest, new BigDecimal("100.0000"), "INR", "Rent");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value("100.0000"));
    }

    @Test
    @DisplayName("POST /api/v1/transfers should return 400 Bad Request when Idempotency-Key header is missing")
    void shouldReturn400WhenIdempotencyKeyMissing() throws Exception {
        CreateTransferRequest request = new CreateTransferRequest(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.0000"), "INR", "Rent");

        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TRANSFER"));
    }
}

