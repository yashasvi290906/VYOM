package com.vyom.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyom.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import java.util.Optional;
import java.util.UUID;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final CorrelationIdFilter correlationIdFilter;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(CorrelationIdFilter correlationIdFilter,
                          JwtAuthenticationFilter jwtAuthenticationFilter,
                          ObjectMapper objectMapper) {
        this.correlationIdFilter = correlationIdFilter;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/health", "/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/transactions/*/reversal").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/disputes/*/resolve").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            String correlationId = (String) request.getAttribute(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
                            if (correlationId == null || correlationId.isBlank()) {
                                correlationId = Optional.ofNullable(request.getHeader("X-Correlation-ID"))
                                        .filter(s -> !s.isBlank())
                                        .orElseGet(() -> "auth-" + UUID.randomUUID().toString().substring(0, 8));
                            }

                            ErrorResponse error = ErrorResponse.of(
                                     401,
                                     "UNAUTHORIZED",
                                     "Authentication is required to access this financial resource: " + authException.getMessage(),
                                     request.getRequestURI(),
                                     correlationId
                            );
                            response.getWriter().write(objectMapper.writeValueAsString(error));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            String correlationId = (String) request.getAttribute(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
                            if (correlationId == null || correlationId.isBlank()) {
                                correlationId = Optional.ofNullable(request.getHeader("X-Correlation-ID"))
                                        .filter(s -> !s.isBlank())
                                        .orElseGet(() -> "auth-" + UUID.randomUUID().toString().substring(0, 8));
                            }

                            ErrorResponse error = ErrorResponse.of(
                                     403,
                                     "ACCESS_DENIED",
                                     "You do not have authorization to access or mutate this financial resource",
                                     request.getRequestURI(),
                                     correlationId
                            );
                            response.getWriter().write(objectMapper.writeValueAsString(error));
                        })
                )
                .addFilterBefore(correlationIdFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
