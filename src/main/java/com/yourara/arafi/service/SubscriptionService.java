package com.yourara.arafi.service;

import com.yourara.arafi.model.Customer;
import com.yourara.arafi.model.Plan;
import com.yourara.arafi.model.Subscription;
import com.yourara.arafi.model.LedgerEntry;
import com.yourara.arafi.repository.CustomerRepository;
import com.yourara.arafi.repository.PlanRepository;
import com.yourara.arafi.repository.SubscriptionRepository;
import com.yourara.arafi.repository.LedgerEntryRepository;
import com.yourara.arafi.model.request.CreateCustomerRequest;
import com.yourara.arafi.model.request.CreatePlanRequest;
import com.yourara.arafi.model.request.CreateSubscriptionRequest;
import com.yourara.arafi.model.response.CustomerResponse;
import com.yourara.arafi.model.response.PlanResponse;
import com.yourara.arafi.model.response.SubscriptionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final CustomerRepository customerRepository;
    private final PlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final NombaClientService nombaClientService;
    private final LedgerEntryRepository ledgerEntryRepository;

    @Transactional
    public CustomerResponse createCustomer(UUID appId, CreateCustomerRequest request) {
        Customer customer = Customer.builder()
                .appId(appId)
                .email(request.getEmail())
                .externalRef(request.getExternalRef())
                .build();
        customerRepository.save(customer);

        return CustomerResponse.builder()
                .id(customer.getId())
                .appId(customer.getAppId())
                .email(customer.getEmail())
                .externalRef(customer.getExternalRef())
                .createdAt(customer.getCreatedAt())
                .build();
    }

    public List<CustomerResponse> getCustomers(UUID appId) {
        return customerRepository.findByAppId(appId).stream()
                .map(c -> CustomerResponse.builder()
                        .id(c.getId())
                        .appId(c.getAppId())
                        .email(c.getEmail())
                        .externalRef(c.getExternalRef())
                        .createdAt(c.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public PlanResponse createPlan(UUID appId, CreatePlanRequest request) {
        Plan plan = Plan.builder()
                .appId(appId)
                .name(request.getName())
                .amountKobo(request.getAmountKobo())
                .billingInterval(request.getInterval())
                .build();
        planRepository.save(plan);

        return PlanResponse.builder()
                .id(plan.getId())
                .appId(plan.getAppId())
                .name(plan.getName())
                .amountKobo(plan.getAmountKobo())
                .interval(plan.getBillingInterval())
                .createdAt(plan.getCreatedAt())
                .build();
    }

    public List<PlanResponse> getPlans(UUID appId) {
        return planRepository.findByAppId(appId).stream()
                .map(p -> PlanResponse.builder()
                        .id(p.getId())
                        .appId(p.getAppId())
                        .name(p.getName())
                        .amountKobo(p.getAmountKobo())
                        .interval(p.getBillingInterval())
                        .createdAt(p.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public SubscriptionResponse createSubscription(UUID appId, CreateSubscriptionRequest request) {
        Customer customer = customerRepository.findByIdAndAppId(request.getCustomerId(), appId)
                .orElseThrow(() -> new IllegalArgumentException("Customer context not found for this app workspace."));

        Plan plan = planRepository.findByIdAndAppId(request.getPlanId(), appId)
                .orElseThrow(() -> new IllegalArgumentException("Billing plan context not found for this app workspace."));

        // 1. Fire a charge request to Nomba client
        String mockIdempotencyKey = UUID.randomUUID().toString();
        Map<String, String> chargeResult = nombaClientService.charge(customer.getEmail(), plan.getAmountKobo(), mockIdempotencyKey);

        if (!"success".equals(chargeResult.get("status"))) {
            throw new IllegalStateException("Payment gateway processing failed: " + chargeResult.get("status"));
        }

        String transactionRef = chargeResult.get("transactionId");

        // 2. Provision and save subscription record
        Instant periodEnd = Instant.now().plus(30, ChronoUnit.DAYS); // default monthly renewal
        if ("yearly".equalsIgnoreCase(plan.getBillingInterval())) {
            periodEnd = Instant.now().plus(365, ChronoUnit.DAYS);
        } else if ("one_time".equalsIgnoreCase(plan.getBillingInterval())) {
            periodEnd = Instant.now().plus(1, ChronoUnit.DAYS);
        }

        Subscription sub = Subscription.builder()
                .appId(appId)
                .customerId(customer.getId())
                .planId(plan.getId())
                .status("active")
                .currentPeriodEnd(periodEnd)
                .nombaReference(transactionRef)
                .build();
        subscriptionRepository.save(sub);

        // 3. Write a corresponding CREDIT ledger transaction
        BigDecimal chargeAmount = BigDecimal.valueOf(plan.getAmountKobo()).divide(BigDecimal.valueOf(100)); // Convert kobo to decimal NGN
        LedgerEntry entry = LedgerEntry.builder()
                .appId(appId)
                .bankAccountNumber("N/A (Subscription Charge)")
                .amount(chargeAmount)
                .entryType("CREDIT")
                .webhookEventId(transactionRef)
                .build();
        ledgerEntryRepository.save(entry);

        return SubscriptionResponse.builder()
                .id(sub.getId())
                .appId(sub.getAppId())
                .customerId(sub.getCustomerId())
                .planId(sub.getPlanId())
                .status(sub.getStatus())
                .currentPeriodEnd(sub.getCurrentPeriodEnd())
                .nombaReference(sub.getNombaReference())
                .createdAt(sub.getCreatedAt())
                .build();
    }

    public List<SubscriptionResponse> getSubscriptions(UUID appId) {
        return subscriptionRepository.findByAppId(appId).stream()
                .map(s -> SubscriptionResponse.builder()
                        .id(s.getId())
                        .appId(s.getAppId())
                        .customerId(s.getCustomerId())
                        .planId(s.getPlanId())
                        .status(s.getStatus())
                        .currentPeriodEnd(s.getCurrentPeriodEnd())
                        .nombaReference(s.getNombaReference())
                        .createdAt(s.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }
}
