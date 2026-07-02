package com.yourara.arafi.controller;

import com.yourara.arafi.repository.LedgerEntryRepository;
import com.yourara.arafi.security.RequestContext;
import com.yourara.arafi.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/balances")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Balances", description = "Endpoints for retrieving ledger balances and details")
public class LedgerBalanceController {

    private final LedgerEntryRepository ledgerRepository;

    @GetMapping
    @Operation(
            summary = "Get wallet balance",
            description = "Computes the total net ledger balance for the authenticated app workspace. Requires developer API key context (arafi_...).",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> getWalletBalance() {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized API context token."));
        }

        BigDecimal calculatedBalance = ledgerRepository.computeBalanceForApp(appId);

        return ResponseEntity.ok(Map.of(
                "app_id", appId,
                "available_balance", calculatedBalance,
                "currency", "NGN",
                "environment_mode", RequestContext.getMode()
        ));
    }
}