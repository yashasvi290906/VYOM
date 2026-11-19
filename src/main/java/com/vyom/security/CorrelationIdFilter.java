package com.vyom.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String CLIENT_IP_MDC_KEY = "clientIp";

    public static final int MAX_CORRELATION_ID_LENGTH = 64;
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request);
        String clientIp = resolveClientIp(request);

        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        MDC.put(CLIENT_IP_MDC_KEY, clientIp);
        request.setAttribute(CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
            MDC.remove(CLIENT_IP_MDC_KEY);
            MDC.remove("userId");
            MDC.remove("userRole");
        }
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (isValidCorrelationId(correlationId)) {
            return correlationId.trim();
        }

        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (isValidCorrelationId(requestId)) {
            return requestId.trim();
        }

        return "corr-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static boolean isValidCorrelationId(String id) {
        if (id == null) {
            return false;
        }
        String trimmed = id.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_CORRELATION_ID_LENGTH) {
            return false;
        }
        return SAFE_ID_PATTERN.matcher(trimmed).matches();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
