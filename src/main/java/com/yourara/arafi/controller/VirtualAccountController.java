package com.yourara.arafi.controller;

import com.yourara.arafi.model.VirtualAccount;
import com.yourara.arafi.repository.VirtualAccountRepository;
import com.yourara.arafi.security.RequestContext;
import com.yourara.arafi.service.NombaClientService;
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
@RequestMapping("/v1/virtual-accounts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Tag(name = "Virtual Accounts", description = "Endpoints for provisioning and querying virtual bank accounts")
public class VirtualAccountController {

    private final NombaClientService nombaService;
    private final VirtualAccountRepository vaRepository;

    @PostMapping
    @Operation(
            summary = "Provision a virtual account",
            description = "Creates a sandbox virtual account mapped to Nomba client services. Requires developer API key context (arafi_...).",
            security = @SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
    )
    public ResponseEntity<?> createVirtualAccount(@RequestBody Map<String, String> request) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized API context. Use Bearer arafi_test_..."));
        }

        String customerRef = request.getOrDefault("customer_ref", "anon_" + UUID.randomUUID().toString().substring(0,8));
        String accountName = request.getOrDefault("account_name", "Arafi Merchant Wallet");

        // Request structural numbers from our client abstraction layer
        Map<String, String> nombaMeta = nombaService.provisionSandboxAccount(accountName, customerRef);

        VirtualAccount va = VirtualAccount.builder()
                .appId(appId)
                .customerReference(customerRef)
                .bankAccountNumber(nombaMeta.get("bankAccountNumber"))
                .bankName(nombaMeta.get("bankName"))
                .currency("NGN")
                .nombaRef(nombaMeta.get("accountRef"))
                .build();

        vaRepository.save(va);

        return ResponseEntity.ok(Map.of(
                "id", va.getId(),
                "bank_account_number", va.getBankAccountNumber(),
                "bank_name", va.getBankName(),
                "currency", va.getCurrency(),
                "customer_ref", va.getCustomerReference(),
                "mode", RequestContext.getMode()
        ));
    }
}