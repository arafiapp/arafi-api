package com.yourara.arafi.security;

import com.yourara.arafi.model.ApiKey;
import com.yourara.arafi.repository.ApiKeyRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SecurityInterceptorFilter extends OncePerRequestFilter {

    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${arafi.jwt.secret}")
    private String jwtSecret;

    private static final List<String> EXCLUDED_PATHS = List.of("/v1/auth/signup", "/v1/auth/login", "/v1/api-docs", "/swagger-ui", "/health");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // 1. Bypass public paths
        if (EXCLUDED_PATHS.stream().anyMatch(path::startsWith)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Intercept Authorization Header
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or malformed Authorization header.");
            return;
        }

        String token = authHeader.substring(7).trim();

        try {
            // 3. ROUTE A: Handle Developer API Keys
            if (token.startsWith("arafi_")) {
                if (token.length() < 12) {
                    sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid API Key format.");
                    return;
                }
                String prefix = token.substring(0, 11);
                List<ApiKey> candidates = apiKeyRepository.findByKeyPrefixAndRevokedAtIsNull(prefix);

                ApiKey validatedKey = candidates.stream()
                        .filter(key -> passwordEncoder.matches(token, key.getKeyHash()))
                        .findFirst()
                        .orElse(null);

                if (validatedKey == null) {
                    sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or revoked API Key.");
                    return;
                }

                RequestContext.setContext(validatedKey.getApp().getId(), validatedKey.getMode());

                org.springframework.security.authentication.UsernamePasswordAuthenticationToken authentication =
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                validatedKey.getApp().getId(),
                                null,
                                java.util.Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_APP"))
                        );
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            // 4. ROUTE B: Handle Frontend Dashboard User JWTs
            else {
                Claims claims = Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtSecret)))
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                UUID userId = UUID.fromString(claims.getSubject());
                RequestContext.setUserId(userId);

                org.springframework.security.authentication.UsernamePasswordAuthenticationToken authentication =
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                userId,
                                null,
                                java.util.Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))
                        );
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
            }

            filterChain.doFilter(request, response);

        } catch (Exception e) {
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication failed: " + e.getMessage());
        } finally {
            // 5. Always flush context clean post-execution
            RequestContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private void sendErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(String.format("{\"error\": \"%s\"}", message));
    }
}