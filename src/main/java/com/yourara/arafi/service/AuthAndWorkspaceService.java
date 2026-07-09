package com.yourara.arafi.service;

import com.yourara.arafi.model.ApiKey;
import com.yourara.arafi.model.App;
import com.yourara.arafi.model.User;
import com.yourara.arafi.model.response.SignupResponse;
import com.yourara.arafi.repository.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AuthAndWorkspaceService {

    private final UserRepository userRepository;
    private final AppRepository appRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${arafi.jwt.secret}")
    private String jwtSecret;

    // 1. HUMAN USER IDENTITY SIGNUP
    @Transactional
    public SignupResponse registerUser(String email, String plaintextPassword) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("A user with this email address already exists.");
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(plaintextPassword))
                .build();
        userRepository.save(user);

        String token = Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(24, ChronoUnit.HOURS)))
                .signWith(Keys.hmacShaKeyFor(getJwtSecretBytes()))
                .compact();

        long appCount = 0;
        String message = "User sign successful";
        return new SignupResponse(message, token, appCount);
    }

    // 2. HUMAN USER LOGIN (Issues stateless JWT token)
    public com.yourara.arafi.model.response.LoginResponse loginUser(String email, String plaintextPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password credentials."));

        if (!passwordEncoder.matches(plaintextPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password credentials.");
        }

        // Generate a 24-hour stateless session token token
        String token = Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(24, ChronoUnit.HOURS)))
                .signWith(Keys.hmacShaKeyFor(getJwtSecretBytes()))
                .compact();

        long appCount = appRepository.countByUserId(user.getId());
        return new com.yourara.arafi.model.response.LoginResponse(token, appCount);
    }

    // 3. WORKSPACE / APP CREATION (Generates independent Sandbox & Live API keys)
    @Transactional
    public com.yourara.arafi.model.response.CreateWorkspaceResponse createNewAppWorkspace(UUID userId, String appName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User context not found."));

        App app = App.builder()
                .user(user)
                .name(appName)
                .status("active")
                .build();
        appRepository.save(app);

        // Manufacture dual keyrings: One for Sandbox testing, one for Live operations
        String testKey = generateAndSaveKey(app, "test");
        String liveKey = generateAndSaveKey(app, "live");
        
        return com.yourara.arafi.model.response.CreateWorkspaceResponse.builder()
                .appId(app.getId())
                .appName(app.getName())
                .status(app.getStatus())
                .sandboxKey(testKey)
                .liveKey(liveKey)
                .build();
    }

    private String generateAndSaveKey(App app, String mode) {
        SecureRandom random = new SecureRandom();
        byte[] values = new byte[24];
        random.nextBytes(values);
        String rawSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(values);

        String prefix = "test".equals(mode) ? "arafi_test_" : "arafi_live_";
        String plaintextApiKey = prefix + rawSecret;

        ApiKey apiKey = ApiKey.builder()
                .app(app)
                .keyPrefix(prefix)
                .keyHash(passwordEncoder.encode(plaintextApiKey))
                .mode(mode)
                .build();
        apiKeyRepository.save(apiKey);

        return plaintextApiKey;
    }

    public List<com.yourara.arafi.model.response.CreateWorkspaceResponse> getWorkspacesForUser(UUID userId) {
        List<App> apps = appRepository.findByUserId(userId);
        List<com.yourara.arafi.model.response.CreateWorkspaceResponse> responses = new ArrayList<>();
        for (App app : apps) {
            List<ApiKey> keys = apiKeyRepository.findByAppIdAndRevokedAtIsNull(app.getId());
            String sandboxKey = keys.stream()
                    .filter(k -> "test".equalsIgnoreCase(k.getMode()))
                    .findFirst()
                    .map(k -> k.getKeyPrefix() + "********************")
                    .orElse("");
            String liveKey = keys.stream()
                    .filter(k -> "live".equalsIgnoreCase(k.getMode()))
                    .findFirst()
                    .map(k -> k.getKeyPrefix() + "********************")
                    .orElse("");

            responses.add(com.yourara.arafi.model.response.CreateWorkspaceResponse.builder()
                    .appId(app.getId())
                    .appName(app.getName())
                    .status(app.getStatus())
                    .sandboxKey(sandboxKey)
                    .liveKey(liveKey)
                    .build());
        }
        return responses;
    }

    public Map<String, String> getWorkspaceApiKeys(UUID userId, UUID appId) {
        App app = appRepository.findById(appId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found."));

        if (!app.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Unauthorized workspace context.");
        }

        List<ApiKey> keys = apiKeyRepository.findByAppIdAndRevokedAtIsNull(appId);
        String sandboxKey = keys.stream()
                .filter(k -> "test".equalsIgnoreCase(k.getMode()))
                .findFirst()
                .map(k -> k.getKeyPrefix() + "********************")
                .orElse("");
        String liveKey = keys.stream()
                .filter(k -> "live".equalsIgnoreCase(k.getMode()))
                .findFirst()
                .map(k -> k.getKeyPrefix() + "********************")
                .orElse("");

        return Map.of(
            "sandbox_key", sandboxKey,
            "live_key", liveKey
        );
    }

    @Transactional
    public Map<String, String> regenerateApiKey(UUID userId, UUID appId, String mode) {
        if (!"test".equalsIgnoreCase(mode) && !"live".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("Invalid mode. Expected 'test' or 'live'.");
        }

        App app = appRepository.findById(appId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found."));

        if (!app.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Unauthorized workspace context.");
        }

        // Revoke existing active key(s) for this mode
        List<ApiKey> activeKeys = apiKeyRepository.findByAppIdAndRevokedAtIsNull(appId);
        for (ApiKey key : activeKeys) {
            if (mode.equalsIgnoreCase(key.getMode())) {
                key.setRevokedAt(Instant.now());
                apiKeyRepository.save(key);
            }
        }

        // Generate and save new key
        String newPlaintextKey = generateAndSaveKey(app, mode.toLowerCase());

        return Map.of(
            "mode", mode.toLowerCase(),
            "api_key", newPlaintextKey
        );
    }

    @Transactional
    public Map<String, Object> updateWebhookSettings(UUID userId, UUID appId, String webhookUrl, String redirectUrl, boolean rotateSecret) {
        App app = appRepository.findById(appId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found."));

        if (!app.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Unauthorized workspace context.");
        }

        if (webhookUrl != null) {
            app.setWebhookUrl(webhookUrl);
        }

        if (redirectUrl != null) {
            app.setRedirectUrl(redirectUrl);
        }

        if (rotateSecret || app.getWebhookSecret() == null) {
            app.setWebhookSecret("arafi_whsec_" + UUID.randomUUID().toString().replace("-", ""));
        }

        appRepository.save(app);

        return Map.of(
            "app_id", app.getId(),
            "webhook_url", app.getWebhookUrl() != null ? app.getWebhookUrl() : "",
            "redirect_url", app.getRedirectUrl() != null ? app.getRedirectUrl() : "",
            "webhook_secret", app.getWebhookSecret()
        );
    }

    private byte[] getJwtSecretBytes() {
        try {
            byte[] decoded = Base64.getDecoder().decode(jwtSecret);
            if (decoded.length >= 32) {
                return decoded;
            }
        } catch (IllegalArgumentException e) {
            // Fall through to hashing
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return digest.digest(jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }
}