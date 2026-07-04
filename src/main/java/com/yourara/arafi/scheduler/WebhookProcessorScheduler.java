package com.yourara.arafi.scheduler;

import com.yourara.arafi.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebhookProcessorScheduler {

    private final SubscriptionService subscriptionService;

    @Scheduled(fixedDelay = 5000)
    public void processWebhooks() {
        try {
            subscriptionService.processReceivedWebhooks();
        } catch (Exception e) {
            System.err.println("Error in WebhookProcessorScheduler: " + e.getMessage());
        }
    }
}
