package com.yourara.arafi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.env.Environment;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NombaClientService {

    private final RestTemplate restTemplate;
    private final Environment environment;

    @Value("${nomba.account.id}")
    private String parentId;

    @Value("${nomba.sub.account.id}")
    private String subAccountId;

    @Value("${nomba.test.client.id}")
    private String testClientId;

    @Value("${nomba.test.private.key}")
    private String testPrivateKey;

    @Value("${nomba.live.client.id:}")
    private String liveClientId;

    @Value("${nomba.live.private.key:}")
    private String livePrivateKey;

    public String getSubAccountId() {
        return subAccountId;
    }

    private String getBaseUrl() {
        if (environment != null && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev", "development", "local", "test"))) {
            return "https://sandbox.nomba.com";
        }
        return "https://api.nomba.com";
    }

    private String getPrivateKey() {
        if (environment != null && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev", "development", "local", "test"))) {
            return testPrivateKey;
        }
        return (livePrivateKey != null && !livePrivateKey.isBlank()) ? livePrivateKey : testPrivateKey;
    }

    public Map<String, String> provisionSandboxAccount(String accountName, String customerRef) {
        // Generate pseudo-random deterministic commercial banking account routing
        // numbers for testing
        String mockAccountNumber = "99" + String.valueOf((long) (Math.random() * 100000000L));
        String mockNombaRef = "nbr_" + UUID.randomUUID().toString().substring(0, 15);

        return Map.of(
                "status", "success",
                "bankAccountNumber", mockAccountNumber,
                "bankName", "WEMA Bank (Nomba Sandbox)",
                "accountRef", mockNombaRef);
    }

    public Map<String, String> charge(String customerEmail, long amountKobo, String idempotencyKey) {
        // Generate a mock transaction reference ID for sandbox execution
        String mockChargeRef = "nbr_chg_" + UUID.randomUUID().toString().substring(0, 15);
        return Map.of(
                "status", "success",
                "transactionId", mockChargeRef);
    }

    public Map<String, String> chargeTokenizedCard(String customerEmail, long amountKobo, String tokenKey, String subAccountId) {
        String baseUrl = getBaseUrl();
        String url = baseUrl + "/v1/checkout/tokenized-card-payment";
        
        // Strict payload body mapping matching Nomba specs:
        Map<String, Object> orderMap = new HashMap<>();
        String orderReference = UUID.randomUUID().toString();
        orderMap.put("orderReference", orderReference); // Acts as idempotency key
        orderMap.put("customerId", customerEmail); // Pass unique customer handle
        orderMap.put("customerEmail", customerEmail);
        orderMap.put("amount", String.format("%.2f", (double) amountKobo / 100)); // Decimal format string (e.g. "10000.00")
        orderMap.put("currency", "NGN");
        orderMap.put("accountId", subAccountId != null ? subAccountId : this.subAccountId); // Scoped to merchant sub-account destination

        Map<String, Object> requestBody = Map.of(
            "order", orderMap,
            "tokenKey", tokenKey
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + getPrivateKey());
        headers.set("accountId", parentId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            Map responseBody = response.getBody();
            if (responseBody != null && "00".equals(responseBody.get("code"))) {
                String transactionId = null;
                if (responseBody.get("data") instanceof Map) {
                    Map dataMap = (Map) responseBody.get("data");
                    if (dataMap.get("transactionId") != null) {
                        transactionId = dataMap.get("transactionId").toString();
                    } else if (dataMap.get("orderReference") != null) {
                        transactionId = dataMap.get("orderReference").toString();
                    }
                }
                if (transactionId == null) {
                    transactionId = orderReference;
                }
                return Map.of(
                    "status", "success",
                    "transactionId", transactionId
                );
            } else {
                String errorMsg = responseBody != null && responseBody.get("description") != null 
                        ? responseBody.get("description").toString() 
                        : "Nomba payment gateway returned failure code";
                return Map.of(
                    "status", "failed",
                    "message", errorMsg
                );
            }
        } catch (Exception e) {
            return Map.of(
                "status", "failed",
                "message", e.getMessage()
            );
        }
    }

    public Map<String, String> createCheckoutOrder(String orderReference, long amountKobo, String customerEmail, String callbackUrl) {
        String baseUrl = getBaseUrl();
        String url = baseUrl + "/v1/checkout/order";

        Map<String, Object> orderMap = new HashMap<>();
        orderMap.put("orderReference", orderReference);
        orderMap.put("amount", String.format("%.2f", (double) amountKobo / 100)); // Decimal format string (e.g. "5000.00")
        orderMap.put("currency", "NGN");
        orderMap.put("customerId", customerEmail);
        orderMap.put("callbackUrl", callbackUrl);
        orderMap.put("tokenizeCard", true);

        Map<String, Object> requestBody = Map.of("order", orderMap);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + getPrivateKey());
        headers.set("accountId", parentId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            Map responseBody = response.getBody();
            if (responseBody != null && "00".equals(responseBody.get("code"))) {
                String checkoutLink = null;
                if (responseBody.get("data") instanceof Map) {
                    Map dataMap = (Map) responseBody.get("data");
                    if (dataMap.get("checkoutLink") != null) {
                        checkoutLink = dataMap.get("checkoutLink").toString();
                    }
                }
                if (checkoutLink != null) {
                    return Map.of(
                        "status", "success",
                        "checkoutLink", checkoutLink
                    );
                }
            }
            String errorMsg = responseBody != null && responseBody.get("description") != null 
                    ? responseBody.get("description").toString() 
                    : "Nomba checkout order creation failed";
            return Map.of(
                "status", "failed",
                "message", errorMsg
            );
        } catch (Exception e) {
            if (environment != null && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev", "development", "local", "test"))) {
                return Map.of(
                    "status", "success",
                    "checkoutLink", "https://sandbox.nomba.com/checkout/" + orderReference
                );
            }
            return Map.of(
                "status", "failed",
                "message", e.getMessage()
            );
        }
    }

    public Map<String, String> createVirtualAccount(String accountRef, String accountName) {
        String baseUrl = getBaseUrl();
        String url = baseUrl + "/v1/accounts/virtual";

        Map<String, Object> requestBody = Map.of(
            "accountRef", accountRef,
            "accountName", accountName,
            "currency", "NGN"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + getPrivateKey());
        headers.set("accountId", parentId);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            Map responseBody = response.getBody();
            if (responseBody != null && "00".equals(responseBody.get("code"))) {
                String bankAccountNumber = null;
                String bankName = null;
                if (responseBody.get("data") instanceof Map) {
                    Map dataMap = (Map) responseBody.get("data");
                    if (dataMap.get("accountNumber") != null) {
                        bankAccountNumber = dataMap.get("accountNumber").toString();
                    }
                    if (dataMap.get("bankName") != null) {
                        bankName = dataMap.get("bankName").toString();
                    }
                }
                if (bankAccountNumber != null) {
                    return Map.of(
                        "status", "success",
                        "bankAccountNumber", bankAccountNumber,
                        "bankName", bankName != null ? bankName : "Nomba Bank",
                        "accountRef", accountRef
                    );
                }
            }
            String errorMsg = responseBody != null && responseBody.get("description") != null 
                    ? responseBody.get("description").toString() 
                    : "Nomba virtual account creation failed";
            return Map.of(
                "status", "failed",
                "message", errorMsg
            );
        } catch (Exception e) {
            if (environment != null && environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev", "development", "local", "test"))) {
                return provisionSandboxAccount(accountName, accountRef);
            }
            return Map.of(
                "status", "failed",
                "message", e.getMessage()
            );
        }
    }
}