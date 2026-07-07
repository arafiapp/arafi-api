package com.yourara.arafi.controller;

import com.yourara.arafi.model.request.CreateCustomerRequest;
import com.yourara.arafi.model.response.CustomerResponse;
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
@RequestMapping("/v1/customers")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Customers", description = "Endpoints for managing billing customers")
public class CustomerController {

    private final SubscriptionService subscriptionService;

    @PostMapping
    @Operation(
            summary = "Create a customer record",
            description = "Creates a billing profile customer mapped to the authenticated app workspace. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> createCustomer(@RequestBody CreateCustomerRequest request) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }

        try {
            CustomerResponse customer = subscriptionService.createCustomer(appId, request);
            return ResponseEntity.ok(customer);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping
    @Operation(
            summary = "List customer records",
            description = "Retrieves all customer records mapped to the authenticated app workspace. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> getCustomers() {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }

        try {
            List<CustomerResponse> customers = subscriptionService.getCustomers(appId);
            return ResponseEntity.ok(customers);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/card")
    @Operation(
            summary = "Delete customer's vaulted payment card",
            description = "Deletes the card token associated with the customer, preventing subsequent silent charges. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> deleteCustomerCard(@PathVariable UUID id) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }

        try {
            subscriptionService.deleteVaultedCard(appId, id);
            return ResponseEntity.ok(java.util.Map.of("message", "Vaulted payment method successfully removed."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{id}/card/tokenize")
    @Operation(
            summary = "Initialize card tokenization flow",
            description = "Creates a checkout link to tokenize/vault a new card for the customer. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> tokenizeCard(
            @PathVariable UUID id,
            @RequestParam(name = "redirect_url", required = false) String redirectUrl) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }

        try {
            String checkoutLink = subscriptionService.createCardTokenizationOrder(appId, id, redirectUrl);
            return ResponseEntity.ok(java.util.Map.of("checkout_url", checkoutLink));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }
}
