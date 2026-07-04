package com.yourara.arafi.controller;

import com.yourara.arafi.model.request.CreateSubscriptionRequest;
import com.yourara.arafi.model.response.SubscriptionResponse;
import com.yourara.arafi.model.response.ErrorResponse;
import com.yourara.arafi.security.RequestContext;
import com.yourara.arafi.service.SubscriptionService;
import com.yourara.arafi.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/subscriptions")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Subscriptions", description = "Endpoints for managing customer billing subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @PostMapping
    @Operation(
            summary = "Subscribe a customer to a billing plan",
            description = "Subscribes a customer to a plan, charges their payment account via Nomba, and logs credit to the app ledger. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> createSubscription(@RequestBody CreateSubscriptionRequest request) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }

        try {
            SubscriptionResponse subscription = subscriptionService.createSubscription(appId, request);
            return ResponseEntity.ok(subscription);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping
    @Operation(
            summary = "List customer subscriptions",
            description = "Retrieves all subscription records registered under the authenticated app workspace. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> getSubscriptions() {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }

        try {
            List<SubscriptionResponse> subscriptions = subscriptionService.getSubscriptions(appId);
            return ResponseEntity.ok(subscriptions);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/run-renewals")
    @Operation(
            summary = "Manually trigger subscription renewal check",
            description = "Triggers the renewal processing worker immediately for active subscriptions past their expiration date. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> runRenewals() {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }

        try {
            subscriptionService.processSubscriptionRenewals();
            return ResponseEntity.ok(java.util.Map.of("message", "Subscription renewals processed successfully."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/run-webhooks")
    @Operation(
            summary = "Manually trigger pending webhook events processing",
            description = "Triggers the webhook processing worker immediately to process received webhook events. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> runWebhooks() {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }

        try {
            subscriptionService.processReceivedWebhooks();
            return ResponseEntity.ok(java.util.Map.of("message", "Webhook processing completed."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }
}
