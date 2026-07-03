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
    public ResponseEntity<?> createVirtualAccount(@RequestBody com.yourara.arafi.model.request.CreateVirtualAccountRequest request) {
        UUID appId = RequestContext.getAppId();
        if (appId == null) {
            return ResponseEntity.status(401).body(new com.yourara.arafi.model.response.ErrorResponse("Unauthorized API context. Use Bearer arafi_test_..."));
        }

        String customerRef = request.getCustomerRef() != null ? request.getCustomerRef() : "anon_" + UUID.randomUUID().toString().substring(0,8);
        String accountName = request.getAccountName() != null ? request.getAccountName() : "Arafi Merchant Wallet";

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

        return ResponseEntity.ok(com.yourara.arafi.model.response.VirtualAccountResponse.builder()
                .id(va.getId())
                .bankAccountNumber(va.getBankAccountNumber())
                .bankName(va.getBankName())
                .currency(va.getCurrency())
                .customerRef(va.getCustomerReference())
                .mode(RequestContext.getMode())
                .build());
    }
}