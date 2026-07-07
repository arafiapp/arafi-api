package com.yourara.arafi.controller;

import com.yourara.arafi.security.RequestContext;
import com.yourara.arafi.service.AuthAndWorkspaceService;
import com.yourara.arafi.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workspaces")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Workspaces", description = "Endpoints for managing workspace environments and developer credentials")
public class AppWorkspaceController {

    private final AuthAndWorkspaceService workspaceService;

    @PostMapping("/create")
    @Operation(
            summary = "Create an app workspace",
            description = "Generates dual API keys (Sandbox & Live) for developers. Requires user authentication context (JWT).",
            security = @SecurityRequirement(name = OpenApiConfig.USER_JWT_SCHEME)
    )
    public ResponseEntity<?> createWorkspace(@RequestBody com.yourara.arafi.model.request.CreateWorkspaceRequest request) {
        try {
            UUID userId = RequestContext.getUserId();
            if (userId == null) {
                return ResponseEntity.status(401).body(new com.yourara.arafi.model.response.ErrorResponse("Unauthorized user context."));
            }

            com.yourara.arafi.model.response.CreateWorkspaceResponse workspace = workspaceService.createNewAppWorkspace(userId, request.getName());
            return ResponseEntity.ok(workspace);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new com.yourara.arafi.model.response.ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping
    @Operation(
            summary = "Get user's app workspaces",
            description = "Retrieves all app workspaces created by the authenticated user. Requires user authentication context (JWT).",
            security = @SecurityRequirement(name = OpenApiConfig.USER_JWT_SCHEME)
    )
    public ResponseEntity<?> getWorkspaces() {
        try {
            UUID userId = RequestContext.getUserId();
            if (userId == null) {
                return ResponseEntity.status(401).body(new com.yourara.arafi.model.response.ErrorResponse("Unauthorized user context."));
            }

            return ResponseEntity.ok(workspaceService.getWorkspacesForUser(userId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new com.yourara.arafi.model.response.ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/{appId}/api-keys")
    @Operation(
            summary = "Get active API keys for a workspace",
            description = "Retrieves active masked sandbox and live keys for a workspace owned by the authenticated user. Requires user authentication context (JWT).",
            security = @SecurityRequirement(name = OpenApiConfig.USER_JWT_SCHEME)
    )
    public ResponseEntity<?> getWorkspaceApiKeys(@PathVariable UUID appId) {
        try {
            UUID userId = RequestContext.getUserId();
            if (userId == null) {
                return ResponseEntity.status(401).body(new com.yourara.arafi.model.response.ErrorResponse("Unauthorized user context."));
            }

            return ResponseEntity.ok(workspaceService.getWorkspaceApiKeys(userId, appId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new com.yourara.arafi.model.response.ErrorResponse(e.getMessage()));
        }
    }
}