package com.yourara.arafi.controller;

import com.yourara.arafi.service.AuthAndWorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Authentication", description = "Endpoints for dashboard user registration and session initiation")
public class AuthController {

    private final AuthAndWorkspaceService authService;

    @PostMapping("/signup")
    @Operation(summary = "Register a new user account", description = "Creates a database credentials record for the user email and password.")
    public ResponseEntity<?> signup(@RequestBody Map<String, String> request) {
        try {
            authService.registerUser(request.get("email"), request.get("password"));
            return ResponseEntity.ok(Map.of("message", "User account registered successfully."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    @Operation(summary = "Initiate user dashboard session", description = "Validates credentials and generates a stateless JWT bearer token.")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        try {
            String token = authService.loginUser(request.get("email"), request.get("password"));
            return ResponseEntity.ok(Map.of("access_token", token));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}