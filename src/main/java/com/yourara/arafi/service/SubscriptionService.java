package com.yourara.arafi.service;

import com.yourara.arafi.model.*;
import com.yourara.arafi.repository.CustomerRepository;
import com.yourara.arafi.repository.PlanRepository;
import com.yourara.arafi.repository.SubscriptionRepository;
import com.yourara.arafi.repository.LedgerEntryRepository;
import com.yourara.arafi.repository.AppRepository;
import com.yourara.arafi.repository.WebhookRepository;
import org.springframework.web.client.RestTemplate;
import com.yourara.arafi.model.request.CreateCustomerRequest;
import com.yourara.arafi.model.request.CreatePlanRequest;
import com.yourara.arafi.model.request.CreateSubscriptionRequest;
import com.yourara.arafi.model.response.CustomerResponse;
import com.yourara.arafi.model.response.PlanResponse;
import com.yourara.arafi.model.response.SubscriptionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
    private final ResendEmailService resendEmailService;
    private final AppRepository appRepository;
    private final RestTemplate restTemplate;
    private final WebhookRepository webhookRepository;

    @Value("${nomba.callback.url:https://arafi-api.onrender.com/v1/checkout/callback}")
    private String nombaCallbackUrl;

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

    private Instant calculatePeriodEnd(String billingInterval) {
        Instant periodEnd = Instant.now().plus(30, ChronoUnit.DAYS); // default monthly renewal
        if ("yearly".equalsIgnoreCase(billingInterval)) {
            periodEnd = Instant.now().plus(365, ChronoUnit.DAYS);
        } else if ("one_time".equalsIgnoreCase(billingInterval)) {
            periodEnd = Instant.now().plus(1, ChronoUnit.DAYS);
        }
        return periodEnd;
    }

    @Transactional
    public SubscriptionResponse createSubscription(UUID appId, CreateSubscriptionRequest request) {
        Customer customer = customerRepository.findByIdAndAppId(request.getCustomerId(), appId)
                .orElseThrow(() -> new IllegalArgumentException("Customer context not found for this app workspace."));

        Plan plan = planRepository.findByIdAndAppId(request.getPlanId(), appId)
                .orElseThrow(() -> new IllegalArgumentException("Billing plan context not found for this app workspace."));


        String status = "PENDING";
        String transactionRef = null;
        String tokenKey = customer.getNombaTokenKey();
        String virtualAccountNumber = customer.getVirtualAccountNumber();
        String paymentMethod = request.getPaymentMethod();
        Instant periodEnd = null;
        String checkoutUrl = null;

        BigDecimal amountDecimal = BigDecimal.valueOf(plan.getAmountKobo()).divide(BigDecimal.valueOf(100)); // Convert kobo to NGN

        if ("CARD".equalsIgnoreCase(paymentMethod)) {
            if (tokenKey != null && !tokenKey.isBlank()) {
                // RECURRING USER: Programmatic charge via token
                Map<String, String> chargeResult = nombaClientService.chargeTokenizedCard(
                        customer.getEmail(),
                        plan.getAmountKobo(),
                        tokenKey,
                        nombaClientService.getSubAccountId()
                );

                if ("success".equals(chargeResult.get("status"))) {
                    status = "ACTIVE";
                    transactionRef = chargeResult.get("transactionId");
                    periodEnd = calculatePeriodEnd(plan.getBillingInterval());

                    LedgerEntry entry = LedgerEntry.builder()
                            .appId(appId)
                            .bankAccountNumber("N/A (Card Payment)")
                            .amount(amountDecimal)
                            .entryType("CREDIT")
                            .webhookEventId(transactionRef)
                            .build();
                    ledgerEntryRepository.save(entry);

                    resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), amountDecimal, "card", "ACTIVE");
                } else {
                    throw new IllegalStateException("Payment gateway processing failed: " + chargeResult.get("message"));
                }
            } else {
                // FIRST-TIME USER: No card token saved yet!
                status = "PENDING";
                UUID subId = UUID.randomUUID();
                String orderReference = subId.toString();

                String callbackUrl = nombaCallbackUrl != null ? nombaCallbackUrl : "https://arafi-api.onrender.com/v1/checkout/callback";
                Map<String, String> checkoutResult = nombaClientService.createCheckoutOrder(
                        orderReference,
                        plan.getAmountKobo(),
                        customer.getEmail(),
                        callbackUrl
                );

                if ("success".equals(checkoutResult.get("status"))) {
                    checkoutUrl = checkoutResult.get("checkoutLink");
                    transactionRef = orderReference;
                } else {
                    throw new IllegalStateException("Failed to create checkout order with Nomba: " + checkoutResult.get("message"));
                }
            }

        } else if ("BANK_TRANSFER".equalsIgnoreCase(paymentMethod)) {
            // Check if the customer already has an account number from an earlier checkout intent
            if (virtualAccountNumber == null || virtualAccountNumber.isBlank()) {
                // FIRST-TIME USER: Call Nomba out-of-band to allocate a static bank account right now
                String accountRef = "arafi_vban_" + customer.getId().toString();
                String accountName = "ARAFI * " + customer.getEmail();
                Map<String, String> accountDetails = nombaClientService.createVirtualAccount(accountRef, accountName);

                if ("success".equals(accountDetails.get("status"))) {
                    virtualAccountNumber = accountDetails.get("bankAccountNumber");

                    // Persist it back to the customer profile permanently
                    customer.setVirtualAccountNumber(virtualAccountNumber);
                    customerRepository.save(customer);
                } else {
                    throw new IllegalStateException("Failed to provision static virtual account with Nomba: " + accountDetails.get("message"));
                }
            }

            status = "PENDING"; // Remains pending until webhook receiver picks up the transfer credit notice
            resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), amountDecimal, "bank_transfer", "PENDING");

        } else {
            throw new IllegalArgumentException("Invalid payment method. Expected 'CARD' or 'BANK_TRANSFER'.");
        }

        // Provision and save subscription record
        Subscription sub = Subscription.builder()
                .appId(appId)
                .customerId(customer.getId())
                .planId(plan.getId())
                .status(status)
                .currentPeriodEnd(periodEnd)
                .nombaTokenKey(tokenKey)
                .virtualAccountNumber(virtualAccountNumber)
                .nombaReference(transactionRef)
                .checkoutUrl(checkoutUrl)
                .build();

        if ("CARD".equalsIgnoreCase(paymentMethod) && (tokenKey == null || tokenKey.isBlank()) && transactionRef != null) {
            try {
                sub.setId(UUID.fromString(transactionRef));
            } catch (Exception e) {
                // Ignore
            }
        }
        subscriptionRepository.save(sub);

        return SubscriptionResponse.builder()
                .id(sub.getId())
                .appId(sub.getAppId())
                .customerId(sub.getCustomerId())
                .planId(sub.getPlanId())
                .status(sub.getStatus())
                .currentPeriodEnd(sub.getCurrentPeriodEnd())
                .nombaTokenKey(sub.getNombaTokenKey())
                .virtualAccountNumber(sub.getVirtualAccountNumber())
                .nombaReference(sub.getNombaReference())
                .checkoutUrl(sub.getCheckoutUrl())
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
                        .nombaTokenKey(s.getNombaTokenKey())
                        .virtualAccountNumber(s.getVirtualAccountNumber())
                        .nombaReference(s.getNombaReference())
                        .createdAt(s.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public void processSubscriptionRenewals() {
        System.out.println("Starting Subscription Renewal Worker...");
        List<Subscription> expiredSubscriptions = subscriptionRepository.findByStatusAndCurrentPeriodEndBefore("ACTIVE", Instant.now());
        
        for (Subscription sub : expiredSubscriptions) {
            try {
                Customer customer = customerRepository.findById(sub.getCustomerId()).orElse(null);
                Plan plan = planRepository.findById(sub.getPlanId()).orElse(null);
                
                if (customer == null || plan == null) {
                    System.out.println("Customer or Plan not found for subscription: " + sub.getId());
                    continue;
                }
                
                BigDecimal amountDecimal = BigDecimal.valueOf(plan.getAmountKobo()).divide(BigDecimal.valueOf(100));
                
                boolean isCard = sub.getNombaTokenKey() != null && !sub.getNombaTokenKey().isBlank();
                
                if (isCard) {
                    Map<String, String> chargeResult = nombaClientService.chargeTokenizedCard(
                            customer.getEmail(),
                            plan.getAmountKobo(),
                            sub.getNombaTokenKey(),
                            nombaClientService.getSubAccountId()
                    );
                    
                    if ("success".equals(chargeResult.get("status"))) {
                        sub.setStatus("ACTIVE");
                        sub.setCurrentPeriodEnd(calculatePeriodEnd(plan.getBillingInterval()));
                        sub.setNombaReference(chargeResult.get("transactionId"));
                        subscriptionRepository.save(sub);
                        
                        LedgerEntry entry = LedgerEntry.builder()
                                .appId(sub.getAppId())
                                .bankAccountNumber("N/A (Card Payment)")
                                .amount(amountDecimal)
                                .entryType("CREDIT")
                                .webhookEventId(chargeResult.get("transactionId"))
                                .build();
                        ledgerEntryRepository.save(entry);
                        
                        resendEmailService.sendBillingAlert(sub.getAppId(), customer.getEmail(), customer.getEmail(), amountDecimal, "card", "ACTIVE");
                        System.out.println("Successfully renewed card subscription: " + sub.getId());
                    } else {
                        sub.setStatus("EXPIRED");
                        
                        try {
                            String callbackUrl = nombaCallbackUrl != null ? nombaCallbackUrl : "https://arafi-api.onrender.com/v1/checkout/callback";
                            Map<String, String> checkoutResult = nombaClientService.createCheckoutOrder(
                                    sub.getId().toString(),
                                    plan.getAmountKobo(),
                                    customer.getEmail(),
                                    callbackUrl
                            );
                            if ("success".equals(checkoutResult.get("status"))) {
                                sub.setCheckoutUrl(checkoutResult.get("checkoutLink"));
                                sub.setNombaReference(sub.getId().toString());
                            }
                        } catch (Exception ex) {
                            System.err.println("Failed to pre-generate recovery checkout order: " + ex.getMessage());
                        }
                        subscriptionRepository.save(sub);
                        
                        resendEmailService.sendBillingAlert(sub.getAppId(), customer.getEmail(), customer.getEmail(), amountDecimal, "card (Failed/Expired)", "EXPIRED");
                        
                        appRepository.findById(sub.getAppId()).ifPresent(app -> {
                            if (app.getWebhookUrl() != null && !app.getWebhookUrl().isBlank()) {
                                triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), sub.getAppId(), sub.getCustomerId(), sub.getPlanId(), null, "EXPIRED", "payment_failed");
                            }
                        });
                        System.out.println("Card subscription charge failed, marked EXPIRED: " + sub.getId());
                    }
                } else {
                    sub.setStatus("EXPIRED");
                    subscriptionRepository.save(sub);
                    
                    resendEmailService.sendBillingAlert(sub.getAppId(), customer.getEmail(), customer.getEmail(), amountDecimal, "bank_transfer (Renewal Reminder)", "EXPIRED");
                    
                    appRepository.findById(sub.getAppId()).ifPresent(app -> {
                        if (app.getWebhookUrl() != null && !app.getWebhookUrl().isBlank()) {
                            triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), sub.getAppId(), sub.getCustomerId(), sub.getPlanId(), null, "EXPIRED", "payment_failed");
                        }
                    });
                    System.out.println("Bank Transfer subscription expired, marked EXPIRED: " + sub.getId());
                }
            } catch (Exception e) {
                System.err.println("Error renewing subscription: " + sub.getId() + ", " + e.getMessage());
            }
        }
    }

    @Transactional
    public void processReceivedWebhooks() {
        List<WebhookEvent> pendingEvents = webhookRepository.findByProcessingStatusAndIsSignatureVerifiedTrue("received");
        if (pendingEvents.isEmpty()) {
            return;
        }

        System.out.println("Processing " + pendingEvents.size() + " pending webhook events...");

        for (WebhookEvent event : pendingEvents) {
            try {
                Map<String, Object> payloadMap = event.getRawPayload();
                String eventType = event.getEventType();
                
                Object dataObj = payloadMap.get("data");
                if (dataObj instanceof Map) {
                    Map<String, Object> data = (Map<String, Object>) dataObj;
                    
                    Object amountObj = data.get("amount");
                    BigDecimal amount = null;
                    if (amountObj instanceof Number) {
                        amount = new BigDecimal(amountObj.toString());
                    } else if (amountObj instanceof String) {
                        amount = new BigDecimal((String) amountObj);
                    }
                    
                    String transactionId = event.getNombaEventId();
                    if (transactionId == null) {
                        transactionId = (String) data.get("transactionId");
                    }
                    
                    if ("payment_success".equalsIgnoreCase(eventType) || data.containsKey("tokenKey") || data.containsKey("orderReference")) {
                        String orderReference = (String) data.get("orderReference");
                        String tokenKey = null;
                        if (data.containsKey("tokenKey") && data.get("tokenKey") != null) {
                            tokenKey = data.get("tokenKey").toString();
                        } else if (data.containsKey("metadata") && data.get("metadata") instanceof Map) {
                            Map metadata = (Map) data.get("metadata");
                            if (metadata.get("tokenKey") != null) {
                                tokenKey = metadata.get("tokenKey").toString();
                            }
                        }
                        
                        if (orderReference != null && tokenKey != null && amount != null) {
                            processCardPaymentSuccess(orderReference, tokenKey, amount, transactionId);
                        } else {
                            System.out.println("Card payment success missing required fields in webhook: " + event.getId());
                        }
                    } 
                    else if (data.containsKey("bankAccountNumber")) {
                        String bankAccountNumber = (String) data.get("bankAccountNumber");
                        if (bankAccountNumber != null && amount != null) {
                            processVirtualAccountPayment(bankAccountNumber, amount, transactionId);
                        } else {
                            System.out.println("Bank transfer credit missing required fields in webhook: " + event.getId());
                        }
                    }
                }
                
                event.setProcessingStatus("processed");
                webhookRepository.save(event);
            } catch (Exception e) {
                System.err.println("Failed to process WebhookEvent " + event.getId() + ": " + e.getMessage());
                event.setProcessingStatus("failed");
                webhookRepository.save(event);
            }
        }
    }

    private void processCardPaymentSuccess(String orderReference, String tokenKey, BigDecimal amount, String transactionId) {
        Subscription foundSubscription = null;
        try {
            UUID subId = UUID.fromString(orderReference);
            foundSubscription = subscriptionRepository.findById(subId).orElse(null);
        } catch (IllegalArgumentException e) {
            // Ignore
        }
        if (foundSubscription == null) {
            foundSubscription = subscriptionRepository.findByNombaReference(orderReference).orElse(null);
        }

        if (foundSubscription != null) {
            final Subscription subscription = foundSubscription;
            Customer customer = customerRepository.findById(subscription.getCustomerId()).orElse(null);
            if (customer != null) {
                customer.setNombaTokenKey(tokenKey);
                customerRepository.save(customer);
            }

            subscription.setStatus("ACTIVE");
            subscription.setNombaTokenKey(tokenKey);
            subscription.setNombaReference(transactionId);
            subscription.setCurrentPeriodEnd(calculatePeriodEnd(planRepository.findById(subscription.getPlanId()).map(Plan::getBillingInterval).orElse("monthly")));
            subscriptionRepository.save(subscription);

            LedgerEntry entry = LedgerEntry.builder()
                    .appId(subscription.getAppId())
                    .bankAccountNumber("N/A (Card Payment)")
                    .amount(amount)
                    .entryType("CREDIT")
                    .webhookEventId(transactionId)
                    .build();
            ledgerEntryRepository.save(entry);

            appRepository.findById(subscription.getAppId()).ifPresent(app -> {
                if (app.getWebhookUrl() != null && !app.getWebhookUrl().isBlank()) {
                    triggerMerchantCallback(app.getWebhookUrl(), subscription.getId(), subscription.getAppId(), subscription.getCustomerId(), subscription.getPlanId(), amount, "ACTIVE", null);
                }
            });

            if (customer != null) {
                resendEmailService.sendBillingAlert(subscription.getAppId(), customer.getEmail(), customer.getEmail(), amount, "card", "ACTIVE");
            }
            System.out.println("Card payment processed and vaulted successfully for subscription: " + subscription.getId());
        } else {
            throw new IllegalArgumentException("No subscription found matching order reference: " + orderReference);
        }
    }

    private void processVirtualAccountPayment(String virtualAccountNumber, BigDecimal amount, String transactionId) {
        List<Subscription> subscriptions = subscriptionRepository.findByVirtualAccountNumber(virtualAccountNumber);
        if (subscriptions.isEmpty()) {
            throw new IllegalArgumentException("No subscriptions found for virtual account number: " + virtualAccountNumber);
        }

        Subscription subscription = subscriptions.stream()
                .filter(s -> "PENDING".equalsIgnoreCase(s.getStatus()) || "EXPIRED".equalsIgnoreCase(s.getStatus()))
                .findFirst()
                .orElse(subscriptions.get(0));

        subscription.setStatus("ACTIVE");
        
        Instant baseTime = Instant.now();
        if (subscription.getCurrentPeriodEnd() != null && subscription.getCurrentPeriodEnd().isAfter(Instant.now())) {
            baseTime = subscription.getCurrentPeriodEnd();
        }
        
        Plan plan = planRepository.findById(subscription.getPlanId()).orElse(null);
        String interval = plan != null ? plan.getBillingInterval() : "monthly";
        Instant periodEnd = baseTime.plus(30, ChronoUnit.DAYS);
        if ("yearly".equalsIgnoreCase(interval)) {
            periodEnd = baseTime.plus(365, ChronoUnit.DAYS);
        } else if ("one_time".equalsIgnoreCase(interval)) {
            periodEnd = baseTime.plus(1, ChronoUnit.DAYS);
        }
        
        subscription.setCurrentPeriodEnd(periodEnd);
        subscription.setNombaReference(transactionId);
        subscriptionRepository.save(subscription);

        LedgerEntry entry = LedgerEntry.builder()
                .appId(subscription.getAppId())
                .bankAccountNumber(virtualAccountNumber)
                .amount(amount)
                .entryType("CREDIT")
                .webhookEventId(transactionId)
                .build();
        ledgerEntryRepository.save(entry);

        appRepository.findById(subscription.getAppId()).ifPresent(app -> {
            if (app.getWebhookUrl() != null && !app.getWebhookUrl().isBlank()) {
                triggerMerchantCallback(app.getWebhookUrl(), subscription.getId(), subscription.getAppId(), subscription.getCustomerId(), subscription.getPlanId(), amount, "ACTIVE", null);
            }
        });

        customerRepository.findById(subscription.getCustomerId()).ifPresent(customer -> {
            resendEmailService.sendBillingAlert(subscription.getAppId(), customer.getEmail(), customer.getEmail(), amount, "bank_transfer", "ACTIVE");
        });
        System.out.println("Virtual account payment processed successfully for subscription: " + subscription.getId());
    }

    private void triggerMerchantCallback(String webhookUrl, UUID subscriptionId, UUID appId, UUID customerId, UUID planId, BigDecimal amount, String status, String reason) {
        try {
            Map<String, Object> dataMap = new java.util.HashMap<>();
            dataMap.put("subscriptionId", subscriptionId.toString());
            dataMap.put("appId", appId.toString());
            dataMap.put("customerId", customerId.toString());
            dataMap.put("planId", planId.toString());
            dataMap.put("status", status);
            if (amount != null) {
                dataMap.put("amount", amount.toString());
            }
            if (reason != null) {
                dataMap.put("reason", reason);
            }

            Map<String, Object> payload = Map.of(
                "event", "ACTIVE".equalsIgnoreCase(status) ? "subscription.active" : "subscription.expired",
                "data", dataMap
            );
            restTemplate.postForEntity(webhookUrl, payload, String.class);
            System.out.println("Successfully triggered merchant callback webhook to: " + webhookUrl);
        } catch (Exception e) {
            System.err.println("Failed to dispatch merchant callback webhook to " + webhookUrl + ": " + e.getMessage());
        }
    }
}
