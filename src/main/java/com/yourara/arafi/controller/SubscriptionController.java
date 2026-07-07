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

    @DeleteMapping("/{id}/cancel")
    @Operation(
            summary = "Cancel a subscription",
            description = "Cancels a subscription immediately or at the end of the billing period. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> cancelSubscription(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean immediately
    ) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }
        try {
            SubscriptionResponse subscription = subscriptionService.cancelSubscription(appId, id, immediately);
            return ResponseEntity.ok(subscription);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{id}/pause")
    @Operation(
            summary = "Pause a subscription",
            description = "Pauses subscription access and billing immediately. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> pauseSubscription(@PathVariable UUID id) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }
        try {
            SubscriptionResponse subscription = subscriptionService.pauseSubscription(appId, id);
            return ResponseEntity.ok(subscription);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{id}/resume")
    @Operation(
            summary = "Resume a subscription",
            description = "Resumes subscription access and charges immediately if expired. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> resumeSubscription(@PathVariable UUID id) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }
        try {
            SubscriptionResponse subscription = subscriptionService.resumeSubscription(appId, id);
            return ResponseEntity.ok(subscription);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PutMapping("/{id}/plan")
    @Operation(
            summary = "Upgrade or downgrade a subscription's plan",
            description = "Changes the plan of the subscription, calculating prorated credits and charging/refunding the difference. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> changePlan(
            @PathVariable UUID id,
            @RequestBody java.util.Map<String, String> payload
    ) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }
        try {
            String planIdStr = payload.get("planId");
            if (planIdStr == null || planIdStr.isBlank()) {
                return ResponseEntity.badRequest().body(new ErrorResponse("Missing required 'planId' parameter."));
            }
            UUID newPlanId = UUID.fromString(planIdStr);
            SubscriptionResponse subscription = subscriptionService.changePlan(appId, id, newPlanId);
            return ResponseEntity.ok(subscription);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{id}/verify-payment")
    @Operation(
            summary = "Manually check status and verify a subscription payment",
            description = "Checks the transaction payment status directly on Nomba and activates the subscription if successful. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> verifyPayment(@PathVariable UUID id) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }
        try {
            java.util.Map<String, Object> result = subscriptionService.verifySubscriptionPayment(appId, id);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }
}
