package com.yourara.arafi.scheduler;

import com.yourara.arafi.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SubscriptionRenewalScheduler {

    private final SubscriptionService subscriptionService;

    @Scheduled(cron = "0 0 * * * *")
    public void runRenewals() {
        try {
            subscriptionService.processSubscriptionRenewals();
        } catch (Exception e) {
            System.err.println("Error in SubscriptionRenewalScheduler: " + e.getMessage());
        }
    }
}
