package com.yourara.arafi.controller;

import com.yourara.arafi.model.EmailTemplate;
import com.yourara.arafi.model.request.SaveEmailTemplateRequest;
import com.yourara.arafi.model.response.ErrorResponse;
import com.yourara.arafi.security.RequestContext;
import com.yourara.arafi.service.EmailTemplateService;
import com.yourara.arafi.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/v1/settings/emails")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Email Settings", description = "Endpoints for configuring merchant outbound email templates")
public class EmailSettingsController {

    private final EmailTemplateService emailTemplateService;

    @PostMapping
    @Operation(
            summary = "Save or update email template settings",
            description = "Saves or updates the customized email template parameters (subject, body). Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> saveTemplate(@RequestBody SaveEmailTemplateRequest request) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }
        if (request.getSubject() == null || request.getSubject().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Subject is required."));
        }
        if (request.getBody() == null || request.getBody().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Body is required."));
        }
        try {
            EmailTemplate template = emailTemplateService.saveOrUpdateTemplate(appId, request.getSubject(), request.getBody());
            return ResponseEntity.ok(template);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }
}
