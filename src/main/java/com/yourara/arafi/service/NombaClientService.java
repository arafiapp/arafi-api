package com.yourara.arafi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NombaClientService {

    public Map<String, String> provisionSandboxAccount(String accountName, String customerRef) {
        // Generate pseudo-random deterministic commercial banking account routing numbers for testing
        String mockAccountNumber = "99" + String.valueOf((long) (Math.random() * 100000000L));
        String mockNombaRef = "nbr_" + UUID.randomUUID().toString().substring(0, 15);

        return Map.of(
                "bankAccountNumber", mockAccountNumber,
                "bankName", "WEMA Bank (Nomba Sandbox)",
                "accountRef", mockNombaRef
        );
    }
}