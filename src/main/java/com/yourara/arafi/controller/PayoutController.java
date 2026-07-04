package com.yourara.arafi.controller;

import com.yourara.arafi.config.OpenApiConfig;
import com.yourara.arafi.model.Payout;
import com.yourara.arafi.model.request.PayoutRequest;
import com.yourara.arafi.model.response.ErrorResponse;
import com.yourara.arafi.security.RequestContext;
import com.yourara.arafi.service.PayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/payouts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Payouts", description = "Endpoints for managing developer settlements and balance cashouts")
public class PayoutController {

    private final PayoutService payoutService;

    @PostMapping
    @Operation(
            summary = "Request a balance payout / settlement",
            description = "Submits a payout request to settle funds to the bank account. Immediately debits the available balance. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> requestPayout(@RequestBody PayoutRequest request) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }

        try {
            Payout payout = payoutService.requestPayout(
                    appId,
                    request.getAmount(),
                    request.getBankAccountNumber(),
                    request.getBankCode(),
                    request.getBankName()
            );
            return ResponseEntity.ok(payout);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping
    @Operation(
            summary = "List payout requests",
            description = "Retrieves all historic payouts for the authenticated app workspace. Requires API key authentication.",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> getPayouts() {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }

        try {
            List<Payout> payouts = payoutService.getPayoutsForApp(appId);
            return ResponseEntity.ok(payouts);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }
}
