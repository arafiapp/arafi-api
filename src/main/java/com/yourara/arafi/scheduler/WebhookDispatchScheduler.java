package com.yourara.arafi.scheduler;

import com.yourara.arafi.model.WebhookDispatch;
import com.yourara.arafi.repository.WebhookDispatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.yourara.arafi.repository.AppRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@RequiredArgsConstructor
public class WebhookDispatchScheduler {

    private final WebhookDispatchRepository webhookDispatchRepository;
    private final AppRepository appRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 5000) // Checks every 5 seconds
    @Transactional
    public void processPendingWebhooks() {
        List<WebhookDispatch> pending = webhookDispatchRepository
                .findByStatusAndNextAttemptAtBeforeAndAttemptsLessThan("PENDING", Instant.now(), 5);

        if (pending.isEmpty()) {
            return;
        }

        System.out.println("Webhook Outbox: Processing " + pending.size() + " pending webhook dispatches...");

        for (WebhookDispatch dispatch : pending) {
            dispatch.setAttempts(dispatch.getAttempts() + 1);
            dispatch.setLastAttemptAt(Instant.now());

            try {
                // 1. Serialize payload map to JSON string
                String jsonPayload = objectMapper.writeValueAsString(dispatch.getPayload());

                // 2. Build headers
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                // 3. Retrieve webhook secret and sign if available
                appRepository.findById(dispatch.getAppId()).ifPresent(app -> {
                    if (app.getWebhookSecret() != null) {
                        String signature = calculateHmac(jsonPayload, app.getWebhookSecret());
                        headers.set("arafi-signature", signature);
                    }
                });

                // 4. Perform the HTTP POST
                HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);
                ResponseEntity<String> response = restTemplate.postForEntity(
                        dispatch.getWebhookUrl(),
                        entity,
                        String.class
                );

                if (response.getStatusCode().is2xxSuccessful()) {
                    dispatch.setStatus("SUCCESS");
                    dispatch.setErrorMessage(null);
                    System.out.println("Webhook Outbox: Successfully dispatched event " + dispatch.getEventType() 
                            + " (ID: " + dispatch.getId() + ") to " + dispatch.getWebhookUrl());
                } else {
                    handleFailure(dispatch, "Received non-2xx status code: " + response.getStatusCode().value());
                }
            } catch (Exception e) {
                handleFailure(dispatch, e.getMessage());
            }

            webhookDispatchRepository.save(dispatch);
        }
    }

    private void handleFailure(WebhookDispatch dispatch, String error) {
        dispatch.setErrorMessage(error);
        System.err.println("Webhook Outbox: Failed to dispatch event " + dispatch.getEventType() 
                + " (ID: " + dispatch.getId() + ") to " + dispatch.getWebhookUrl() + ". Error: " + error);

        if (dispatch.getAttempts() >= 5) {
            dispatch.setStatus("FAILED");
            System.err.println("Webhook Outbox: Max delivery attempts reached for dispatch: " + dispatch.getId());
        } else {
            // Exponential backoff: 2^attempts minutes (e.g., 2, 4, 8, 16 minutes)
            long delayMinutes = (long) Math.pow(2, dispatch.getAttempts());
            dispatch.setNextAttemptAt(Instant.now().plus(delayMinutes, ChronoUnit.MINUTES));
            System.out.println("Webhook Outbox: Rescheduled dispatch ID " + dispatch.getId() 
                    + " to " + dispatch.getNextAttemptAt() + " (after " + delayMinutes + " mins delay).");
        }
    }

    private String calculateHmac(String data, String key) {
        try {
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            hmac.init(secretKey);
            byte[] rawHash = hmac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(rawHash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC signature", e);
        }
    }
}
