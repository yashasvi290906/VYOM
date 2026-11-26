package com.vyom.exception;

import com.vyom.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWalletNotFound(WalletNotFoundException ex, HttpServletRequest request) {
        log.warn("Wallet not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex, HttpServletRequest request) {
        log.warn("Insufficient funds conflict: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", ex.getMessage(), request);
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ResponseEntity<ErrorResponse> handleCurrencyMismatch(CurrencyMismatchException ex, HttpServletRequest request) {
        log.warn("Currency mismatch: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "CURRENCY_MISMATCH", ex.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
        log.warn("Idempotency conflict: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransfer(InvalidTransferException ex, HttpServletRequest request) {
        log.warn("Invalid transfer request: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_TRANSFER", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Validation failure on {}: {}", request.getRequestURI(), message);
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY", "Malformed or unreadable JSON request body", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        log.warn("Missing required header: {}", ex.getHeaderName());
        return buildResponse(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Required header '" + ex.getHeaderName() + "' is missing", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.error("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION", "Database constraint violation occurred", request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        log.warn("Authentication failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateIdentityException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateIdentity(DuplicateIdentityException ex, HttpServletRequest request) {
        log.warn("Duplicate customer identity on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "DUPLICATE_IDENTITY", ex.getMessage(), request);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransactionNotFound(TransactionNotFoundException ex, HttpServletRequest request) {
        log.warn("Transaction not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(OverRefundException.class)
    public ResponseEntity<ErrorResponse> handleOverRefund(OverRefundException ex, HttpServletRequest request) {
        log.warn("Over-refund conflict: {}", ex.getMessage());
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, "OVER_REFUND_EXCEEDED", ex.getMessage(), request);
    }

    @ExceptionHandler(DisputeConflictException.class)
    public ResponseEntity<ErrorResponse> handleDisputeConflict(DisputeConflictException ex, HttpServletRequest request) {
        log.warn("Dispute conflict: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "DISPUTE_CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(MerchantNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMerchantNotFound(MerchantNotFoundException ex, HttpServletRequest request) {
        log.warn("Merchant not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(MerchantMembershipNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMerchantMembershipNotFound(MerchantMembershipNotFoundException ex, HttpServletRequest request) {
        log.warn("Merchant membership not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidMerchantTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMerchantTransition(InvalidMerchantTransitionException ex, HttpServletRequest request) {
        log.warn("Invalid merchant lifecycle transition: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "INVALID_MERCHANT_TRANSITION", ex.getMessage(), request);
    }

    @ExceptionHandler(LastOwnerProtectionException.class)
    public ResponseEntity<ErrorResponse> handleLastOwnerProtection(LastOwnerProtectionException ex, HttpServletRequest request) {
        log.warn("Last active owner protection violation: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "LAST_OWNER_PROTECTION_VIOLATION", ex.getMessage(), request);
    }

    @ExceptionHandler(InsufficientMerchantPrivilegeException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientMerchantPrivilege(InsufficientMerchantPrivilegeException ex, HttpServletRequest request) {
        log.warn("Insufficient merchant privilege: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "INSUFFICIENT_MERCHANT_PRIVILEGE", ex.getMessage(), request);
    }

    @ExceptionHandler(MerchantAccountSuspendedException.class)
    public ResponseEntity<ErrorResponse> handleMerchantAccountSuspended(MerchantAccountSuspendedException ex, HttpServletRequest request) {
        log.warn("Merchant account suspended: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "MERCHANT_ACCOUNT_SUSPENDED", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateMembershipException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateMembership(DuplicateMembershipException ex, HttpServletRequest request) {
        log.warn("Duplicate merchant membership: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "DUPLICATE_MEMBERSHIP", ex.getMessage(), request);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        log.warn("Payment order not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(CheckoutSessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCheckoutSessionNotFound(CheckoutSessionNotFoundException ex, HttpServletRequest request) {
        log.warn("Checkout session not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "CHECKOUT_SESSION_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(OrderAlreadyPaidException.class)
    public ResponseEntity<ErrorResponse> handleOrderAlreadyPaid(OrderAlreadyPaidException ex, HttpServletRequest request) {
        log.warn("Payment order already paid: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "ORDER_ALREADY_PAID", ex.getMessage(), request);
    }

    @ExceptionHandler(OrderExpiredException.class)
    public ResponseEntity<ErrorResponse> handleOrderExpired(OrderExpiredException ex, HttpServletRequest request) {
        log.warn("Payment order expired: {}", ex.getMessage());
        return buildResponse(HttpStatus.GONE, "ORDER_EXPIRED", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOrderState(InvalidOrderStateException ex, HttpServletRequest request) {
        log.warn("Invalid payment order state: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_ORDER_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateMerchantOrderIdException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateMerchantOrderId(DuplicateMerchantOrderIdException ex, HttpServletRequest request) {
        log.warn("Duplicate merchant order ID: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "DUPLICATE_MERCHANT_ORDER_ID", ex.getMessage(), request);
    }

    @ExceptionHandler(WebhookEndpointNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWebhookEndpointNotFound(WebhookEndpointNotFoundException ex, HttpServletRequest request) {
        log.warn("Webhook endpoint not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "WEBHOOK_ENDPOINT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateWebhookEndpointException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateWebhookEndpoint(DuplicateWebhookEndpointException ex, HttpServletRequest request) {
        log.warn("Duplicate webhook endpoint: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "DUPLICATE_WEBHOOK_ENDPOINT", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidWebhookEventException.class)
    public ResponseEntity<ErrorResponse> handleInvalidWebhookEvent(InvalidWebhookEventException ex, HttpServletRequest request) {
        log.warn("Invalid webhook event: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK_EVENT", ex.getMessage(), request);
    }

    @ExceptionHandler(SSRFProtectionException.class)
    public ResponseEntity<ErrorResponse> handleSSRFProtection(SSRFProtectionException ex, HttpServletRequest request) {
        log.warn("SSRF protection violation on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "SSRF_PROTECTION_VIOLATION", ex.getMessage(), request);
    }

    @ExceptionHandler(WebhookCryptoException.class)
    public ResponseEntity<ErrorResponse> handleWebhookCrypto(WebhookCryptoException ex, HttpServletRequest request) {
        log.error("Webhook cryptographic error on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_CRYPTO_ERROR", "A cryptographic failure occurred processing the webhook secret", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        log.warn("Illegal state conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "ILLEGAL_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Invalid argument on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage(), request);
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        log.warn("Method argument type mismatch on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Parameter '" + ex.getName() + "' is invalid", request);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("Resource not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleCatchAll(Exception ex, HttpServletRequest request) {
        log.error("Unhandled internal server error on {}:", request.getRequestURI(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "An unexpected error occurred. Please contact system support.", request);
    }

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String errorCode, String message, HttpServletRequest request) {
        String correlationId = org.slf4j.MDC.get(com.vyom.security.CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        if (correlationId == null || correlationId.isBlank()) {
            Object attr = request.getAttribute(com.vyom.security.CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
            if (attr instanceof String s && !s.isBlank()) {
                correlationId = s;
            }
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = Optional.ofNullable(request.getHeader("X-Correlation-ID"))
                    .filter(s -> !s.isBlank())
                    .orElseGet(() -> "corr-" + UUID.randomUUID().toString().substring(0, 8));
        }

        ErrorResponse response = ErrorResponse.of(
                status.value(),
                errorCode,
                message,
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(status).body(response);
    }
}

