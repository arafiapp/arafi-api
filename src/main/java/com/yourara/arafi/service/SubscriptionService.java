package com.yourara.arafi.service;

import com.yourara.arafi.model.*;
import com.yourara.arafi.repository.CustomerRepository;
import com.yourara.arafi.repository.PlanRepository;
import com.yourara.arafi.repository.SubscriptionRepository;
import com.yourara.arafi.repository.LedgerEntryRepository;
import com.yourara.arafi.repository.AppRepository;
import com.yourara.arafi.repository.WebhookRepository;
import com.yourara.arafi.repository.WebhookDispatchRepository;
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
    private final WebhookDispatchRepository webhookDispatchRepository;

    @Value("${nomba.callback.url:https://arafi-api.onrender.com/v1/checkout/callback}")
    private String nombaCallbackUrl;

    @Transactional
    public CustomerResponse createCustomer(UUID appId, CreateCustomerRequest request) {
        String mode = com.yourara.arafi.security.RequestContext.getMode() != null 
                ? com.yourara.arafi.security.RequestContext.getMode() 
                : "test";
        Customer customer = Customer.builder()
                .appId(appId)
                .email(request.getEmail())
                .externalRef(request.getExternalRef())
                .mode(mode)
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
        String resolvedRedirectUrl = request.getRedirectUrl();
        if (resolvedRedirectUrl == null || resolvedRedirectUrl.isBlank()) {
            resolvedRedirectUrl = "https://arafi-platform.vercel.app/checkout/success";
        }

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

                Map<String, String> checkoutResult = nombaClientService.createCheckoutOrder(
                        orderReference,
                        plan.getAmountKobo(),
                        customer.getEmail(),
                        resolvedRedirectUrl
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

        String mode = com.yourara.arafi.security.RequestContext.getMode() != null 
                ? com.yourara.arafi.security.RequestContext.getMode() 
                : "test";
        // Provision and save subscription record
        Subscription sub = Subscription.builder()
                .appId(appId)
                .customerId(customer.getId())
                .planId(plan.getId())
                .status(status)
                .currentPeriodEnd(periodEnd)
                .nombaTokenKey("CARD".equalsIgnoreCase(paymentMethod) ? tokenKey : null)
                .virtualAccountNumber(virtualAccountNumber)
                .nombaReference(transactionRef)
                .checkoutUrl(checkoutUrl)
                .mode(mode)
                .redirectUrl(resolvedRedirectUrl)
                .build();

        if ("CARD".equalsIgnoreCase(paymentMethod) && (tokenKey == null || tokenKey.isBlank()) && transactionRef != null) {
            try {
                sub.setId(UUID.fromString(transactionRef));
            } catch (Exception e) {
                // Ignore
            }
        }
        subscriptionRepository.save(sub);

        return mapToResponse(sub);
    }

    public List<SubscriptionResponse> getSubscriptions(UUID appId) {
        return subscriptionRepository.findByAppId(appId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void processSubscriptionRenewals() {
        System.out.println("Starting Subscription Renewal Worker...");
        List<Subscription> expiredSubscriptions = subscriptionRepository.findByStatusAndCurrentPeriodEndBefore("ACTIVE", Instant.now());
        
        for (Subscription sub : expiredSubscriptions) {
            try {
                com.yourara.arafi.security.RequestContext.setContext(sub.getAppId(), sub.getMode());
                
                if (Boolean.TRUE.equals(sub.getPaused())) {
                    continue;
                }
                
                if (Boolean.TRUE.equals(sub.getCancelAtPeriodEnd())) {
                    sub.setStatus("CANCELLED");
                    subscriptionRepository.save(sub);
                    
                    Customer customer = customerRepository.findById(sub.getCustomerId()).orElse(null);
                    if (customer != null) {
                        BigDecimal planAmt = planRepository.findById(sub.getPlanId())
                                .map(p -> BigDecimal.valueOf(p.getAmountKobo()).divide(BigDecimal.valueOf(100)))
                                .orElse(BigDecimal.ZERO);
                        resendEmailService.sendBillingAlert(sub.getAppId(), customer.getEmail(), customer.getEmail(), planAmt, "system (Cancelled at period end)", "CANCELLED");
                    }
                    
                    appRepository.findById(sub.getAppId()).ifPresent(app -> {
                        if (app.getWebhookUrl() != null && !app.getWebhookUrl().isBlank()) {
                            triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), sub.getAppId(), sub.getCustomerId(), sub.getPlanId(), null, "CANCELLED", "cancel_at_period_end_fired");
                        }
                    });
                    System.out.println("Subscription cancelled at period end: " + sub.getId());
                    continue;
                }

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
                            String callbackUrl = sub.getRedirectUrl();
                            if (callbackUrl == null || callbackUrl.isBlank()) {
                                callbackUrl = "https://arafi-platform.vercel.app/checkout/success";
                            }
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
            } finally {
                com.yourara.arafi.security.RequestContext.clear();
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
            try {
                com.yourara.arafi.security.RequestContext.setContext(foundSubscription.getAppId(), foundSubscription.getMode());
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
            } finally {
                com.yourara.arafi.security.RequestContext.clear();
            }
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

        try {
            com.yourara.arafi.security.RequestContext.setContext(subscription.getAppId(), subscription.getMode());
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
        } finally {
            com.yourara.arafi.security.RequestContext.clear();
        }
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

            String eventType = "ACTIVE".equalsIgnoreCase(status) ? "subscription.active" : "subscription.expired";
            Map<String, Object> payload = Map.of(
                "event", eventType,
                "data", dataMap
            );

            WebhookDispatch dispatch = WebhookDispatch.builder()
                    .appId(appId)
                    .webhookUrl(webhookUrl)
                    .eventType(eventType)
                    .payload(payload)
                    .status("PENDING")
                    .attempts(0)
                    .nextAttemptAt(Instant.now())
                    .build();

            webhookDispatchRepository.save(dispatch);
            System.out.println("Queued merchant webhook callback to outbox for app: " + appId + ", event: " + eventType);
        } catch (Exception e) {
            System.err.println("Failed to queue merchant callback webhook to " + webhookUrl + ": " + e.getMessage());
        }
    }

    @Transactional
    public SubscriptionResponse cancelSubscription(UUID appId, UUID subscriptionId, boolean immediately) {
        Subscription sub = subscriptionRepository.findByIdAndAppId(subscriptionId, appId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found in this app workspace."));

        if (immediately) {
            sub.setStatus("CANCELLED");
            sub.setCurrentPeriodEnd(Instant.now());
        } else {
            sub.setCancelAtPeriodEnd(true);
        }
        subscriptionRepository.save(sub);

        if (immediately) {
            Customer customer = customerRepository.findById(sub.getCustomerId()).orElse(null);
            if (customer != null) {
                resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), BigDecimal.ZERO, "card/bank", "CANCELLED");
            }
            appRepository.findById(appId).ifPresent(app -> {
                if (app.getWebhookUrl() != null && !app.getWebhookUrl().isBlank()) {
                    triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), appId, sub.getCustomerId(), sub.getPlanId(), null, "CANCELLED", "user_cancelled");
                }
            });
        }

        return mapToResponse(sub);
    }

    @Transactional
    public SubscriptionResponse pauseSubscription(UUID appId, UUID subscriptionId) {
        Subscription sub = subscriptionRepository.findByIdAndAppId(subscriptionId, appId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found in this app workspace."));

        sub.setPaused(true);
        sub.setStatus("PAUSED");
        subscriptionRepository.save(sub);

        appRepository.findById(appId).ifPresent(app -> {
            if (app.getWebhookUrl() != null && !app.getWebhookUrl().isBlank()) {
                triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), appId, sub.getCustomerId(), sub.getPlanId(), null, "PAUSED", "subscription_paused");
            }
        });

        return mapToResponse(sub);
    }

    @Transactional
    public SubscriptionResponse resumeSubscription(UUID appId, UUID subscriptionId) {
        Subscription sub = subscriptionRepository.findByIdAndAppId(subscriptionId, appId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found in this app workspace."));

        sub.setPaused(false);
        
        Customer customer = customerRepository.findById(sub.getCustomerId())
                .orElseThrow(() -> new IllegalStateException("Customer profile not found."));
        Plan plan = planRepository.findById(sub.getPlanId())
                .orElseThrow(() -> new IllegalStateException("Billing plan not found."));

        if (sub.getCurrentPeriodEnd() != null && sub.getCurrentPeriodEnd().isAfter(Instant.now())) {
            sub.setStatus("ACTIVE");
            subscriptionRepository.save(sub);
        } else {
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
                            .appId(appId)
                            .bankAccountNumber("N/A (Card Payment)")
                            .amount(amountDecimal)
                            .entryType("CREDIT")
                            .webhookEventId(chargeResult.get("transactionId"))
                            .build();
                    ledgerEntryRepository.save(entry);
                    
                    resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), amountDecimal, "card", "ACTIVE");
                } else {
                    sub.setStatus("EXPIRED");
                    subscriptionRepository.save(sub);
                    throw new IllegalStateException("Failed to charge vaulted card for resuming subscription: " + chargeResult.get("message"));
                }
            } else {
                sub.setStatus("PENDING");
                subscriptionRepository.save(sub);
                resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), amountDecimal, "bank_transfer", "PENDING");
            }
        }

        return mapToResponse(sub);
    }

    @Transactional
    public SubscriptionResponse changePlan(UUID appId, UUID subscriptionId, UUID newPlanId) {
        Subscription sub = subscriptionRepository.findByIdAndAppId(subscriptionId, appId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found in this app workspace."));

        Plan oldPlan = planRepository.findByIdAndAppId(sub.getPlanId(), appId)
                .orElseThrow(() -> new IllegalStateException("Current plan details not found."));

        Plan newPlan = planRepository.findByIdAndAppId(newPlanId, appId)
                .orElseThrow(() -> new IllegalArgumentException("New billing plan not found in this app workspace."));

        if (sub.getPlanId().equals(newPlanId)) {
            return mapToResponse(sub);
        }

        Customer customer = customerRepository.findById(sub.getCustomerId())
                .orElseThrow(() -> new IllegalStateException("Customer context not found."));

        long creditKobo = 0;
        if ("ACTIVE".equalsIgnoreCase(sub.getStatus()) && sub.getCurrentPeriodEnd() != null && sub.getCurrentPeriodEnd().isAfter(Instant.now())) {
            long remainingSeconds = Math.max(0, sub.getCurrentPeriodEnd().getEpochSecond() - Instant.now().getEpochSecond());
            long totalDurationSeconds = "yearly".equalsIgnoreCase(oldPlan.getBillingInterval()) ? 365 * 86400L : 30 * 86400L;
            double fraction = (double) remainingSeconds / totalDurationSeconds;
            creditKobo = (long) (oldPlan.getAmountKobo() * fraction);
        }

        long amountDueKobo = newPlan.getAmountKobo() - creditKobo;

        if (amountDueKobo > 0) {
            BigDecimal amountDecimal = BigDecimal.valueOf(amountDueKobo).divide(BigDecimal.valueOf(100));
            boolean isCard = sub.getNombaTokenKey() != null && !sub.getNombaTokenKey().isBlank();
            if (isCard) {
                Map<String, String> chargeResult = nombaClientService.chargeTokenizedCard(
                        customer.getEmail(),
                        amountDueKobo,
                        sub.getNombaTokenKey(),
                        nombaClientService.getSubAccountId()
                );

                if ("success".equals(chargeResult.get("status"))) {
                    sub.setPlanId(newPlanId);
                    sub.setStatus("ACTIVE");
                    sub.setCurrentPeriodEnd(calculatePeriodEnd(newPlan.getBillingInterval()));
                    sub.setNombaReference(chargeResult.get("transactionId"));
                    subscriptionRepository.save(sub);

                    LedgerEntry entry = LedgerEntry.builder()
                            .appId(appId)
                            .bankAccountNumber("N/A (Card Payment - Plan Change)")
                            .amount(amountDecimal)
                            .entryType("CREDIT")
                            .webhookEventId(chargeResult.get("transactionId"))
                            .build();
                    ledgerEntryRepository.save(entry);

                    resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), amountDecimal, "card (Plan Upgrade)", "ACTIVE");
                } else {
                    throw new IllegalStateException("Failed to process payment upgrade charge on card: " + chargeResult.get("message"));
                }
            } else {
                UUID newSubId = UUID.randomUUID();
                String orderReference = newSubId.toString();
                String callbackUrl = sub.getRedirectUrl();
                if (callbackUrl == null || callbackUrl.isBlank()) {
                    callbackUrl = "https://arafi-platform.vercel.app/checkout/success";
                }
                
                Map<String, String> checkoutResult = nombaClientService.createCheckoutOrder(
                        orderReference,
                        amountDueKobo,
                        customer.getEmail(),
                        callbackUrl
                );

                if ("success".equals(checkoutResult.get("status"))) {
                    sub.setPlanId(newPlanId);
                    sub.setStatus("PENDING");
                    sub.setCheckoutUrl(checkoutResult.get("checkoutLink"));
                    sub.setNombaReference(orderReference);
                    subscriptionRepository.save(sub);

                    resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), amountDecimal, "bank_transfer (Plan Upgrade pending checkout)", "PENDING");
                } else {
                    throw new IllegalStateException("Failed to generate checkout order for plan upgrade: " + checkoutResult.get("message"));
                }
            }
        } else {
            sub.setPlanId(newPlanId);
            sub.setStatus("ACTIVE");
            sub.setCurrentPeriodEnd(calculatePeriodEnd(newPlan.getBillingInterval()));
            subscriptionRepository.save(sub);

            resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), BigDecimal.ZERO, "system_credit", "ACTIVE");
        }

        appRepository.findById(appId).ifPresent(app -> {
            if (app.getWebhookUrl() != null && !app.getWebhookUrl().isBlank()) {
                triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), appId, sub.getCustomerId(), sub.getPlanId(), null, "ACTIVE", "plan_changed");
            }
        });

        return mapToResponse(sub);
    }

    @Transactional
    public void deleteVaultedCard(UUID appId, UUID customerId) {
        Customer customer = customerRepository.findByIdAndAppId(customerId, appId)
                .orElseThrow(() -> new IllegalArgumentException("Customer profile not found."));

        customer.setNombaTokenKey(null);
        customerRepository.save(customer);

        List<Subscription> subs = subscriptionRepository.findByCustomerId(customerId);
        for (Subscription sub : subs) {
            sub.setNombaTokenKey(null);
            subscriptionRepository.save(sub);
        }
        System.out.println("Deleted vaulted payment card for customer: " + customerId + " in app: " + appId);
    }

    @Transactional
    public String createCardTokenizationOrder(UUID appId, UUID customerId, String redirectUrl) {
        Customer customer = customerRepository.findByIdAndAppId(customerId, appId)
                .orElseThrow(() -> new IllegalArgumentException("Customer profile not found."));

        long amountKobo = 1000L; // 10.00 NGN tokenization check
        String orderReference = "card_upd_" + UUID.randomUUID().toString().substring(0, 15);
        String callbackUrl = redirectUrl;
        if (callbackUrl == null || callbackUrl.isBlank()) {
            callbackUrl = "https://arafi-platform.vercel.app/checkout/success";
        }

        Map<String, String> checkoutResult = nombaClientService.createCheckoutOrder(
                orderReference,
                amountKobo,
                customer.getEmail(),
                callbackUrl
        );

        if ("success".equals(checkoutResult.get("status"))) {
            List<Subscription> existingSubs = subscriptionRepository.findByCustomerId(customer.getId());
            UUID planId = null;
            if (!existingSubs.isEmpty()) {
                planId = existingSubs.get(0).getPlanId();
            } else {
                List<Plan> plans = planRepository.findByAppId(appId);
                if (!plans.isEmpty()) {
                    planId = plans.get(0).getId();
                } else {
                    throw new IllegalStateException("At least one plan must exist in this app workspace to initialize card update.");
                }
            }

            Subscription sub = Subscription.builder()
                    .appId(appId)
                    .customerId(customer.getId())
                    .planId(planId)
                    .status("PENDING")
                    .nombaReference(orderReference)
                    .checkoutUrl(checkoutResult.get("checkoutLink"))
                    .mode(com.yourara.arafi.security.RequestContext.getMode() != null ? com.yourara.arafi.security.RequestContext.getMode() : "test")
                    .redirectUrl(callbackUrl)
                    .build();
            try {
                sub.setId(UUID.fromString(orderReference));
            } catch (Exception e) {
                // Ignore
            }
            subscriptionRepository.save(sub);

            return checkoutResult.get("checkoutLink");
        } else {
            throw new IllegalStateException("Failed to create tokenization checkout order: " + checkoutResult.get("message"));
        }
    }

    private SubscriptionResponse mapToResponse(Subscription sub) {
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
                .cancelAtPeriodEnd(sub.getCancelAtPeriodEnd())
                .paused(sub.getPaused())
                .redirectUrl(sub.getRedirectUrl())
                .build();
    }
}
