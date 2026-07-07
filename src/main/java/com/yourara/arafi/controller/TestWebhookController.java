package com.yourara.arafi.controller;

import com.yourara.arafi.model.WebhookEvent;
import com.yourara.arafi.repository.WebhookRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/test")
@RequiredArgsConstructor
@Tag(name = "Test Utilities", description = "Endpoints for test sandbox simulations")
public class TestWebhookController {

    private final WebhookRepository webhookRepository;

    @PostMapping("/nomba-webhook")
    @SecurityRequirements // Public endpoint for testing
    @Operation(
            summary = "Simulate Nomba Webhook (Sandbox Test Tool)",
            description = "Manually pushes a validated card payment success webhook into the queue for a given subscription order reference."
    )
    public ResponseEntity<?> simulateNombaWebhook(@RequestBody TestWebhookRequest request) {
        if (request.getOrderReference() == null || request.getAmount() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderReference and amount are required."));
        }

        String txnId = "WEB-ONLINE_C-mock-" + UUID.randomUUID().toString();
        String tokenKey = request.getTokenKey() != null ? request.getTokenKey() : "nbr_tok_test_mockkey_999";

        // Build mock Nomba payload JSON
        Map<String, Object> transaction = Map.of(
                "fee", 0.28,
                "type", "online_checkout",
                "transactionId", txnId,
                "merchantTxRef", "txref-" + Instant.now().getEpochSecond(),
                "transactionAmount", request.getAmount(),
                "time", Instant.now().toString()
        );

        Map<String, Object> order = Map.of(
                "amount", request.getAmount(),
                "orderId", UUID.randomUUID().toString(),
                "accountId", "mock-parent-account-id",
                "customerEmail", "test-customer@arafi.com",
                "orderReference", request.getOrderReference(),
                "paymentMethod", "card_payment",
                "currency", "NGN"
        );

        Map<String, Object> data = Map.of(
                "merchant", Map.of("userId", "mock-parent-account-id"),
                "transaction", transaction,
                "order", order,
                "tokenKey", tokenKey
        );

        Map<String, Object> rawPayload = Map.of(
                "event_type", "payment_success",
                "requestId", txnId,
                "data", data
        );

        WebhookEvent event = WebhookEvent.builder()
                .nombaEventId(txnId)
                .eventType("payment_success")
                .rawPayload(rawPayload)
                .isSignatureVerified(true) // Automatically trusted for manual simulation
                .processingStatus("received")
                .receivedAt(Instant.now())
                .build();

        webhookRepository.save(event);

        return ResponseEntity.ok(Map.of(
                "message", "Webhook mock event injected successfully",
                "transactionId", txnId,
                "status", "received",
                "payload", rawPayload
        ));
    }

    @Data
    public static class TestWebhookRequest {
        private String orderReference;
        private String tokenKey;
        private BigDecimal amount;
    }
}
