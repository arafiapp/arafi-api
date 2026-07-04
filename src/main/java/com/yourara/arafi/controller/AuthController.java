package com.yourara.arafi.controller;

import com.yourara.arafi.model.request.AuthRequest;
import com.yourara.arafi.model.response.LoginResponse;
import com.yourara.arafi.model.response.SignupResponse;
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
    public ResponseEntity<?> signup(@RequestBody com.yourara.arafi.model.request.AuthRequest request) {
        try {
            SignupResponse response =  authService.registerUser(request.getEmail(), request.getPassword());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new com.yourara.arafi.model.response.ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/login")
    @Operation(summary = "Initiate user dashboard session", description = "Validates credentials and generates a stateless JWT bearer token.")
    public ResponseEntity<?> login(@RequestBody com.yourara.arafi.model.request.AuthRequest request) {
        try {
            com.yourara.arafi.model.response.LoginResponse loginResponse = authService.loginUser(request.getEmail(), request.getPassword());
            return ResponseEntity.ok(loginResponse);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new com.yourara.arafi.model.response.ErrorResponse(e.getMessage()));
        }
    }
}