package com.yourara.arafi.service;

import com.yourara.arafi.model.ApiKey;
import com.yourara.arafi.model.App;
import com.yourara.arafi.model.User;
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
    public void registerUser(String email, String plaintextPassword) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("A user with this email address already exists.");
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(plaintextPassword))
                .build();
        userRepository.save(user);
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
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtSecret)))
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
}