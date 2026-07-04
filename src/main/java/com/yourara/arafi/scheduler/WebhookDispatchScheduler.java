package com.yourara.arafi.scheduler;

import com.yourara.arafi.model.WebhookDispatch;
import com.yourara.arafi.repository.WebhookDispatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@RequiredArgsConstructor
public class WebhookDispatchScheduler {

    private final WebhookDispatchRepository webhookDispatchRepository;
    private final RestTemplate restTemplate;

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
                // Perform the HTTP POST
                ResponseEntity<String> response = restTemplate.postForEntity(
                        dispatch.getWebhookUrl(),
                        dispatch.getPayload(),
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
}
