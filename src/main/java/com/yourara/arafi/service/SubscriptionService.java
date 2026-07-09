package com.yourara.arafi.service;

import com.yourara.arafi.model.*;
import com.yourara.arafi.repository.CustomerRepository;
import com.yourara.arafi.repository.PlanRepository;
import com.yourara.arafi.repository.SubscriptionRepository;
import com.yourara.arafi.repository.LedgerEntryRepository;
import com.yourara.arafi.repository.AppRepository;
import com.yourara.arafi.repository.WebhookRepository;
import com.yourara.arafi.repository.WebhookDispatchRepository;
import com.yourara.arafi.repository.CouponRepository;
import org.springframework.web.client.RestTemplate;
import com.yourara.arafi.model.request.CreateCustomerRequest;
import com.yourara.arafi.model.request.CreatePlanRequest;
import com.yourara.arafi.model.request.CreateSubscriptionRequest;
import com.yourara.arafi.model.response.CustomerResponse;
import com.yourara.arafi.model.response.PlanResponse;
import com.yourara.arafi.model.response.SubscriptionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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
    private final CouponRepository couponRepository;

    // callbackUrl is a BROWSER REDIRECT (not server POST). Nomba appends
    // ?orderReference=xxx
    // to this URL and redirects the user's browser there after checkout completion.
    // The frontend /checkout/callback page reads ?orderReference and calls
    // verify-payment.
    @Value("${nomba.callback.url:https://arafi-platform.vercel.app/checkout/callback}")
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
                .gracePeriodDays(request.getGracePeriodDays())
                .build();
        planRepository.save(plan);

        return PlanResponse.builder()
                .id(plan.getId())
                .appId(plan.getAppId())
                .name(plan.getName())
                .amountKobo(plan.getAmountKobo())
                .interval(plan.getBillingInterval())
                .gracePeriodDays(plan.getGracePeriodDays())
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
                        .gracePeriodDays(p.getGracePeriodDays())
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
                .orElseThrow(
                        () -> new IllegalArgumentException("Billing plan context not found for this app workspace."));

        UUID subId = UUID.randomUUID();
        String status = "PENDING";
        String transactionRef = null;
        String tokenKey = customer.getNombaTokenKey();
        String virtualAccountNumber = customer.getVirtualAccountNumber();
        String paymentMethod = request.getPaymentMethod();
        Instant periodEnd = null;
        String checkoutUrl = null;
        String resolvedRedirectUrl = request.getRedirectUrl();
        if (resolvedRedirectUrl == null || resolvedRedirectUrl.isBlank()) {
            // Fall back to the developer's default redirect URL configured on their app
            App app = appRepository.findById(appId).orElse(null);
            resolvedRedirectUrl = (app != null && app.getRedirectUrl() != null && !app.getRedirectUrl().isBlank())
                    ? app.getRedirectUrl()
                    : null;
        }

        // Resolve coupon discount if specified
        Long discountKobo = 0L;
        String couponCode = null;
        if (request.getCouponCode() != null && !request.getCouponCode().isBlank()) {
            Coupon coupon = couponRepository
                    .findByAppIdAndCodeAndActiveTrue(appId, request.getCouponCode().toUpperCase().trim())
                    .orElse(null);
            if (coupon != null) {
                discountKobo = coupon.getDiscountAmountKobo();
                couponCode = coupon.getCode();
            }
        }

        long baseAmountKobo = plan.getAmountKobo();
        long chargeAmountKobo = Math.max(0L, baseAmountKobo - discountKobo);
        BigDecimal amountDecimal = BigDecimal.valueOf(chargeAmountKobo).divide(BigDecimal.valueOf(100)); // Convert kobo
                                                                                                         // to NGN

        if (paymentMethod == null || paymentMethod.isBlank()) {
            // Arafi Hosted Checkout Selection Portal routing
            String frontendBase = nombaCallbackUrl.replace("/checkout/callback", "");
            checkoutUrl = frontendBase + "/checkout/" + subId.toString();
            status = "PENDING";

        } else if ("CARD".equalsIgnoreCase(paymentMethod)) {
            if (tokenKey != null && !tokenKey.isBlank()) {
                // RECURRING USER: Programmatic charge via token
                Map<String, String> chargeResult = nombaClientService.chargeTokenizedCard(
                        customer.getEmail(),
                        chargeAmountKobo,
                        tokenKey,
                        nombaClientService.getSubAccountId());

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

                    resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), amountDecimal,
                            "card", "ACTIVE", null, null);
                } else {
                    throw new IllegalStateException(
                            "Payment gateway processing failed: " + chargeResult.get("message"));
                }
            } else {
                // FIRST-TIME USER: No card token saved yet!
                status = "PENDING";
                String orderReference = subId.toString();

                // nombaCallbackUrl = frontend /checkout/callback page (browser redirect target)
                // Restrict Nomba's checkout options specifically to Card only
                Map<String, String> checkoutResult = nombaClientService.createCheckoutOrder(
                        orderReference,
                        chargeAmountKobo,
                        customer.getEmail(),
                        nombaCallbackUrl,
                        List.of("Card"));

                if ("success".equals(checkoutResult.get("status"))) {
                    checkoutUrl = checkoutResult.get("checkoutLink");
                    transactionRef = orderReference;
                } else {
                    throw new IllegalStateException(
                            "Failed to create checkout order with Nomba: " + checkoutResult.get("message"));
                }
            }

        } else if ("BANK_TRANSFER".equalsIgnoreCase(paymentMethod)) {
            // Check if the customer already has an account number from an earlier checkout
            // intent
            if (virtualAccountNumber == null || virtualAccountNumber.isBlank()) {
                // Call Nomba out-of-band to allocate a static bank account right now
                String accountRef = "arafi_vban_" + customer.getId().toString();
                String accountName = "ARAFI * " + customer.getEmail();
                Map<String, String> accountDetails = nombaClientService.createVirtualAccount(accountRef, accountName,
                        amountDecimal);

                if ("success".equals(accountDetails.get("status"))) {
                    virtualAccountNumber = accountDetails.get("bankAccountNumber");

                    // Persist it back to the customer profile permanently
                    customer.setVirtualAccountNumber(virtualAccountNumber);
                    customerRepository.save(customer);
                } else {
                    throw new IllegalStateException(
                            "Failed to provision static virtual account with Nomba: " + accountDetails.get("message"));
                }
            } else {
                log.info(
                        "[SubscriptionService] Reusing existing virtual account number from database: {} for customer: {}",
                        virtualAccountNumber, customer.getEmail());
            }

            status = "PENDING"; // Remains pending until webhook receiver picks up the transfer credit notice
            resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), amountDecimal,
                    "bank_transfer", "PENDING", "WEMA Bank (Nomba Sandbox)", virtualAccountNumber);

        } else {
            throw new IllegalArgumentException("Invalid payment method. Expected 'CARD' or 'BANK_TRANSFER'.");
        }

        String mode = com.yourara.arafi.security.RequestContext.getMode() != null
                ? com.yourara.arafi.security.RequestContext.getMode()
                : "test";
        // Provision and save subscription record
        Subscription sub = Subscription.builder()
                .id(subId)
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
                .discountAmountKobo(discountKobo)
                .appliedCouponCode(couponCode)
                .build();

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
        List<Subscription> expiredSubscriptions = subscriptionRepository.findByStatusInAndCurrentPeriodEndBefore(
                List.of("ACTIVE", "PAST_DUE"), Instant.now());

        for (Subscription sub : expiredSubscriptions) {
            try {
                com.yourara.arafi.security.RequestContext.setContext(sub.getAppId(), sub.getMode());

                if (Boolean.TRUE.equals(sub.getPaused())) {
                    continue;
                }

                if (Boolean.TRUE.equals(sub.getCancelAtPeriodEnd())) {
                    sub.setStatus("CANCELLED");
                    sub.setGracePeriodStart(null);
                    sub.setRetryCount(0);
                    subscriptionRepository.save(sub);

                    Customer customer = customerRepository.findById(sub.getCustomerId()).orElse(null);
                    if (customer != null) {
                        BigDecimal planAmt = planRepository.findById(sub.getPlanId())
                                .map(p -> BigDecimal.valueOf(p.getAmountKobo()).divide(BigDecimal.valueOf(100)))
                                .orElse(BigDecimal.ZERO);
                        resendEmailService.sendBillingAlert(sub.getAppId(), customer.getEmail(), customer.getEmail(),
                                planAmt, "system (Cancelled at period end)", "CANCELLED", null, null);
                    }

                    appRepository.findById(sub.getAppId()).ifPresent(app -> {
                        triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), sub.getAppId(),
                                sub.getCustomerId(), sub.getPlanId(), null, "CANCELLED",
                                "cancel_at_period_end_fired");
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

                // Compute charge amount incorporating coupon discount if present
                long discountKobo = sub.getDiscountAmountKobo() != null ? sub.getDiscountAmountKobo() : 0L;
                long chargeAmountKobo = Math.max(0L, plan.getAmountKobo() - discountKobo);
                BigDecimal amountDecimal = BigDecimal.valueOf(chargeAmountKobo).divide(BigDecimal.valueOf(100));

                boolean isCard = sub.getNombaTokenKey() != null && !sub.getNombaTokenKey().isBlank();

                if (isCard) {
                    Map<String, String> chargeResult = nombaClientService.chargeTokenizedCard(
                            customer.getEmail(),
                            chargeAmountKobo,
                            sub.getNombaTokenKey(),
                            nombaClientService.getSubAccountId());

                    if ("success".equals(chargeResult.get("status"))) {
                        sub.setStatus("ACTIVE");
                        sub.setCurrentPeriodEnd(calculatePeriodEnd(plan.getBillingInterval()));
                        sub.setNombaReference(chargeResult.get("transactionId"));
                        sub.setGracePeriodStart(null);
                        sub.setRetryCount(0);
                        subscriptionRepository.save(sub);

                        LedgerEntry entry = LedgerEntry.builder()
                                .appId(sub.getAppId())
                                .bankAccountNumber("N/A (Card Payment)")
                                .amount(amountDecimal)
                                .entryType("CREDIT")
                                .webhookEventId(chargeResult.get("transactionId"))
                                .build();
                        ledgerEntryRepository.save(entry);

                        resendEmailService.sendBillingAlert(sub.getAppId(), customer.getEmail(), customer.getEmail(),
                                amountDecimal, "card", "ACTIVE", null, null);
                        System.out.println("Successfully renewed card subscription: " + sub.getId());
                    } else {
                        // Charge failure: resolve grace period
                        int graceDays = plan.getGracePeriodDays() != null ? plan.getGracePeriodDays() : 0;
                        int allowedGraceDays = Math.min(7, graceDays);

                        if (allowedGraceDays > 0) {
                            if ("ACTIVE".equals(sub.getStatus())) {
                                // Transition from ACTIVE to PAST_DUE
                                sub.setStatus("PAST_DUE");
                                sub.setGracePeriodStart(Instant.now());
                                sub.setRetryCount(1);
                                sub.setCurrentPeriodEnd(Instant.now().plus(1, ChronoUnit.DAYS)); // Retry tomorrow
                                subscriptionRepository.save(sub);

                                appRepository.findById(sub.getAppId()).ifPresent(app -> {
                                    triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), sub.getAppId(),
                                            sub.getCustomerId(), sub.getPlanId(), null, "PAST_DUE",
                                            "payment_failed_retrying");
                                });
                                System.out.println(
                                        "Card charge failed. Subscription transitioned to PAST_DUE (Grace Day 1): "
                                                + sub.getId());
                            } else if ("PAST_DUE".equals(sub.getStatus())) {
                                Instant limitInstant = sub.getGracePeriodStart().plus(allowedGraceDays,
                                        ChronoUnit.DAYS);
                                if (Instant.now().isAfter(limitInstant)) {
                                    // Exceeded grace period
                                    handleSubscriptionFailure(sub, plan, customer, amountDecimal);
                                } else {
                                    sub.setRetryCount((sub.getRetryCount() != null ? sub.getRetryCount() : 0) + 1);
                                    sub.setCurrentPeriodEnd(Instant.now().plus(1, ChronoUnit.DAYS)); // Retry tomorrow
                                    subscriptionRepository.save(sub);
                                    System.out.println("Card charge failed in PAST_DUE state. Rescheduled retry day "
                                            + sub.getRetryCount() + " for sub: " + sub.getId());
                                }
                            }
                        } else {
                            // No grace period
                            handleSubscriptionFailure(sub, plan, customer, amountDecimal);
                        }
                    }
                } else {
                    // Non-card (Bank Transfer) check grace period
                    int graceDays = plan.getGracePeriodDays() != null ? plan.getGracePeriodDays() : 0;
                    int allowedGraceDays = Math.min(7, graceDays);

                    if (allowedGraceDays > 0) {
                        if ("ACTIVE".equals(sub.getStatus())) {
                            sub.setStatus("PAST_DUE");
                            sub.setGracePeriodStart(Instant.now());
                            sub.setRetryCount(1);
                            sub.setCurrentPeriodEnd(Instant.now().plus(1, ChronoUnit.DAYS));
                            subscriptionRepository.save(sub);

                            appRepository.findById(sub.getAppId()).ifPresent(app -> {
                                triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), sub.getAppId(),
                                        sub.getCustomerId(), sub.getPlanId(), null, "PAST_DUE",
                                        "payment_failed_retrying");
                            });
                            System.out.println(
                                    "Bank Transfer subscription period end. Transitioned to PAST_DUE grace period: "
                                            + sub.getId());
                        } else if ("PAST_DUE".equals(sub.getStatus())) {
                            Instant limitInstant = sub.getGracePeriodStart().plus(allowedGraceDays, ChronoUnit.DAYS);
                            if (Instant.now().isAfter(limitInstant)) {
                                handleSubscriptionFailure(sub, plan, customer, amountDecimal);
                            } else {
                                sub.setRetryCount((sub.getRetryCount() != null ? sub.getRetryCount() : 0) + 1);
                                sub.setCurrentPeriodEnd(Instant.now().plus(1, ChronoUnit.DAYS));
                                subscriptionRepository.save(sub);
                            }
                        }
                    } else {
                        handleSubscriptionFailure(sub, plan, customer, amountDecimal);
                    }
                }
            } catch (Exception e) {
                System.err
                        .println("Error processing subscription renewal for ID " + sub.getId() + ": " + e.getMessage());
            } finally {
                com.yourara.arafi.security.RequestContext.clear();
            }
        }
    }

    private void handleSubscriptionFailure(Subscription sub, Plan plan, Customer customer, BigDecimal amountDecimal) {
        sub.setStatus("EXPIRED");
        sub.setGracePeriodStart(null);
        sub.setRetryCount(0);

        try {
            long discountKobo = sub.getDiscountAmountKobo() != null ? sub.getDiscountAmountKobo() : 0L;
            long chargeAmountKobo = Math.max(0L, plan.getAmountKobo() - discountKobo);

            Map<String, String> checkoutResult = nombaClientService.createCheckoutOrder(
                    sub.getId().toString(),
                    chargeAmountKobo,
                    customer.getEmail(),
                    nombaCallbackUrl);
            if ("success".equals(checkoutResult.get("status"))) {
                sub.setCheckoutUrl(checkoutResult.get("checkoutLink"));
                sub.setNombaReference(sub.getId().toString());
            }
        } catch (Exception ex) {
            System.err.println("Failed to pre-generate recovery checkout order: " + ex.getMessage());
        }
        subscriptionRepository.save(sub);

        resendEmailService.sendBillingAlert(sub.getAppId(), customer.getEmail(), customer.getEmail(),
                amountDecimal, "card (Failed/Expired)", "EXPIRED", null, null);

        appRepository.findById(sub.getAppId()).ifPresent(app -> {
            triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), sub.getAppId(),
                    sub.getCustomerId(), sub.getPlanId(), null, "EXPIRED", "payment_failed");
        });
        System.out.println("Card/Transfer subscription renewal failed, marked EXPIRED: " + sub.getId());
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void processReceivedWebhooks() {
        List<WebhookEvent> pendingEvents = webhookRepository.findByProcessingStatus("received");
        if (pendingEvents.isEmpty()) {
            return;
        }

        System.out.println("[WebhookProcessor] Processing " + pendingEvents.size() + " event(s)...");

        for (WebhookEvent event : pendingEvents) {
            try {
                Map<String, Object> payloadMap = event.getRawPayload();
                String eventType = event.getEventType();

                // Bypasses signature check if we are in sandbox/test mode
                if (!event.isSignatureVerified()) {
                    String orderReference = (String) payloadMap.get("requestId");
                    boolean isSandboxEvent = false;
                    if (orderReference != null) {
                        try {
                            UUID subId = UUID.fromString(orderReference);
                            Subscription sub = subscriptionRepository.findById(subId).orElse(null);
                            if (sub == null) {
                                sub = subscriptionRepository.findByNombaReference(orderReference).orElse(null);
                            }
                            if (sub != null && "test".equalsIgnoreCase(sub.getMode())) {
                                isSandboxEvent = true;
                            }
                        } catch (Exception e) {
                            // Ignored
                        }
                    }
                    if (!isSandboxEvent) {
                        System.out.println("[WebhookProcessor] Signature verification failed for live event (ID: "
                                + event.getNombaEventId() + "). Skipping.");
                        event.setProcessingStatus("failed");
                        webhookRepository.save(event);
                        continue;
                    } else {
                        System.out.println(
                                "[WebhookProcessor] Signature verification failed, but bypassing for sandbox/test event (ID: "
                                        + event.getNombaEventId() + ").");
                    }
                }

                System.out.println("[WebhookProcessor] id=" + event.getId() + " eventType=" + eventType + " nombaId="
                        + event.getNombaEventId());

                Object dataObj = payloadMap.get("data");
                if (!(dataObj instanceof Map)) {
                    System.out.println("[WebhookProcessor] No 'data' map in payload — marking failed.");
                    event.setProcessingStatus("failed");
                    webhookRepository.save(event);
                    continue;
                }

                Map<String, Object> data = (Map<String, Object>) dataObj;

                // Nomba payload: amount is at data.transaction.transactionAmount (NOT
                // data.amount)
                Map<String, Object> transaction = data.get("transaction") instanceof Map
                        ? (Map<String, Object>) data.get("transaction")
                        : null;

                BigDecimal amount = null;
                if (transaction != null && transaction.get("transactionAmount") != null) {
                    try {
                        amount = new BigDecimal(transaction.get("transactionAmount").toString());
                    } catch (NumberFormatException nfe) {
                        System.err.println("[WebhookProcessor] Could not parse transactionAmount: "
                                + transaction.get("transactionAmount"));
                    }
                }

                String transactionId = event.getNombaEventId();
                if (transactionId == null && transaction != null && transaction.get("transactionId") != null) {
                    transactionId = transaction.get("transactionId").toString();
                }

                if ("payment_success".equalsIgnoreCase(eventType)) {

                    // Determine channel: card tokenization delivers tokenKey at
                    // data.tokenizedCardData.tokenKey
                    Map<String, Object> tokenizedCardData = data.get("tokenizedCardData") instanceof Map
                            ? (Map<String, Object>) data.get("tokenizedCardData")
                            : null;
                    String tokenKey = tokenizedCardData != null && tokenizedCardData.get("tokenKey") != null
                            ? tokenizedCardData.get("tokenKey").toString()
                            : null;

                    if (tokenKey != null) {
                        // CARD PAYMENT — authoritative tokenKey from HMAC-verified webhook
                        // requestId maps to our orderReference (stored as nombaReference on the
                        // subscription)
                        String orderReference = (String) payloadMap.get("requestId");
                        System.out.println("[WebhookProcessor] Card payment + tokenKey detected. orderRef="
                                + orderReference + " amount=" + amount);
                        if (orderReference != null && amount != null) {
                            processCardPaymentSuccess(orderReference, tokenKey, amount, transactionId);
                        } else {
                            System.out.println("[WebhookProcessor] Card: missing orderReference or amount.");
                        }

                    } else if (transaction != null && transaction.get("aliasAccountNumber") != null) {
                        // BANK TRANSFER — virtual account credit identified by aliasAccountNumber
                        String aliasAccountNumber = transaction.get("aliasAccountNumber").toString();
                        System.out.println("[WebhookProcessor] Bank transfer. aliasAccountNumber=" + aliasAccountNumber
                                + " amount=" + amount);
                        if (amount != null) {
                            processVirtualAccountPayment(aliasAccountNumber, amount, transactionId);
                        } else {
                            System.out.println("[WebhookProcessor] Bank transfer: missing amount.");
                        }

                    } else {
                        System.out.println(
                                "[WebhookProcessor] payment_success but channel unclear. data keys=" + data.keySet()
                                        + " transaction keys=" + (transaction != null ? transaction.keySet() : "null"));
                    }

                } else if ("payment_failed".equalsIgnoreCase(eventType)) {
                    System.out.println("[WebhookProcessor] Payment FAILED for nombaId=" + event.getNombaEventId());

                } else if ("payout_success".equalsIgnoreCase(eventType)
                        || "payout_failed".equalsIgnoreCase(eventType)
                        || "payout_refund".equalsIgnoreCase(eventType)) {
                    System.out.println("[WebhookProcessor] Payout event: " + eventType + " — no handler yet.");

                } else {
                    System.out.println("[WebhookProcessor] Unhandled eventType='" + eventType + "'");
                }

                event.setProcessingStatus("processed");
                webhookRepository.save(event);

            } catch (Exception e) {
                System.err.println("[WebhookProcessor] Error on event " + event.getId() + ": " + e.getMessage());
                e.printStackTrace();
                event.setProcessingStatus("failed");
                webhookRepository.save(event);
            }
        }
    }

    private void processCardPaymentSuccess(String orderReference, String tokenKey, BigDecimal amount,
            String transactionId) {
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
                com.yourara.arafi.security.RequestContext.setContext(foundSubscription.getAppId(),
                        foundSubscription.getMode());
                final Subscription subscription = foundSubscription;
                Customer customer = customerRepository.findById(subscription.getCustomerId()).orElse(null);
                if (customer != null) {
                    // Only update tokenKey if we actually have one — do not null-out an existing
                    // vault entry
                    if (tokenKey != null && !tokenKey.isBlank() && !"N/A".equals(tokenKey)) {
                        customer.setNombaTokenKey(tokenKey);
                        customerRepository.save(customer);
                    }
                }

                subscription.setStatus("ACTIVE");
                // Only update tokenKey on subscription if we have one
                if (tokenKey != null && !tokenKey.isBlank() && !"N/A".equals(tokenKey)) {
                    subscription.setNombaTokenKey(tokenKey);
                }
                subscription.setNombaReference(transactionId);
                subscription.setCurrentPeriodEnd(calculatePeriodEnd(planRepository.findById(subscription.getPlanId())
                        .map(Plan::getBillingInterval).orElse("monthly")));
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
                    triggerMerchantCallback(app.getWebhookUrl(), subscription.getId(), subscription.getAppId(),
                            subscription.getCustomerId(), subscription.getPlanId(), amount, "ACTIVE", null);
                });

                if (customer != null) {
                    resendEmailService.sendBillingAlert(subscription.getAppId(), customer.getEmail(),
                            customer.getEmail(), amount, "card", "ACTIVE", null, null);
                }
                System.out.println(
                        "Card payment processed and vaulted successfully for subscription: " + subscription.getId());
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
            throw new IllegalArgumentException(
                    "No subscriptions found for virtual account number: " + virtualAccountNumber);
        }

        Subscription subscription = subscriptions.stream()
                .filter(s -> "PENDING".equalsIgnoreCase(s.getStatus()) || "EXPIRED".equalsIgnoreCase(s.getStatus()))
                .findFirst()
                .orElse(subscriptions.get(0));

        try {
            com.yourara.arafi.security.RequestContext.setContext(subscription.getAppId(), subscription.getMode());
            subscription.setStatus("ACTIVE");

            Instant baseTime = Instant.now();
            if (subscription.getCurrentPeriodEnd() != null
                    && subscription.getCurrentPeriodEnd().isAfter(Instant.now())) {
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
                triggerMerchantCallback(app.getWebhookUrl(), subscription.getId(), subscription.getAppId(),
                        subscription.getCustomerId(), subscription.getPlanId(), amount, "ACTIVE", null);
            });

            customerRepository.findById(subscription.getCustomerId()).ifPresent(customer -> {
                resendEmailService.sendBillingAlert(subscription.getAppId(), customer.getEmail(), customer.getEmail(),
                        amount, "bank_transfer", "ACTIVE", "WEMA Bank (Nomba Sandbox)", virtualAccountNumber);
            });
            System.out.println(
                    "Virtual account payment processed successfully for subscription: " + subscription.getId());
        } finally {
            com.yourara.arafi.security.RequestContext.clear();
        }
    }

    private void triggerMerchantCallback(String webhookUrl, UUID subscriptionId, UUID appId, UUID customerId,
            UUID planId, BigDecimal amount, String status, String reason) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            webhookUrl = "https://mock.arafi.com/webhook-fallback";
        }
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
                    "data", dataMap);

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
            System.out
                    .println("Queued merchant webhook callback to outbox for app: " + appId + ", event: " + eventType);
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
                resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), BigDecimal.ZERO,
                        "card/bank", "CANCELLED", null, null);
            }
            appRepository.findById(appId).ifPresent(app -> {
                triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), appId, sub.getCustomerId(),
                        sub.getPlanId(), null, "CANCELLED", "user_cancelled");
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
            triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), appId, sub.getCustomerId(), sub.getPlanId(),
                    null, "PAUSED", "subscription_paused");
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
                        nombaClientService.getSubAccountId());

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

                    resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), amountDecimal,
                            "card", "ACTIVE", null, null);
                } else {
                    sub.setStatus("EXPIRED");
                    subscriptionRepository.save(sub);
                    throw new IllegalStateException(
                            "Failed to charge vaulted card for resuming subscription: " + chargeResult.get("message"));
                }
            } else {
                sub.setStatus("PENDING");
                subscriptionRepository.save(sub);
                resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), amountDecimal,
                        "bank_transfer", "PENDING", "WEMA Bank (Nomba Sandbox)", sub.getVirtualAccountNumber());
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
        if ("ACTIVE".equalsIgnoreCase(sub.getStatus()) && sub.getCurrentPeriodEnd() != null
                && sub.getCurrentPeriodEnd().isAfter(Instant.now())) {
            long remainingSeconds = Math.max(0,
                    sub.getCurrentPeriodEnd().getEpochSecond() - Instant.now().getEpochSecond());
            long totalDurationSeconds = "yearly".equalsIgnoreCase(oldPlan.getBillingInterval()) ? 365 * 86400L
                    : 30 * 86400L;
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
                        nombaClientService.getSubAccountId());

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

                    resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), amountDecimal,
                            "card (Plan Upgrade)", "ACTIVE", null, null);
                } else {
                    throw new IllegalStateException(
                            "Failed to process payment upgrade charge on card: " + chargeResult.get("message"));
                }
            } else {
                UUID newSubId = UUID.randomUUID();
                String orderReference = newSubId.toString();
                Map<String, String> checkoutResult = nombaClientService.createCheckoutOrder(
                        orderReference,
                        amountDueKobo,
                        customer.getEmail(),
                        nombaCallbackUrl);

                if ("success".equals(checkoutResult.get("status"))) {
                    sub.setPlanId(newPlanId);
                    sub.setStatus("PENDING");
                    sub.setCheckoutUrl(checkoutResult.get("checkoutLink"));
                    sub.setNombaReference(orderReference);
                    subscriptionRepository.save(sub);

                    resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), amountDecimal,
                            "bank_transfer (Plan Upgrade pending checkout)", "PENDING", "WEMA Bank (Nomba Sandbox)",
                            sub.getVirtualAccountNumber());
                } else {
                    throw new IllegalStateException(
                            "Failed to generate checkout order for plan upgrade: " + checkoutResult.get("message"));
                }
            }
        } else {
            sub.setPlanId(newPlanId);
            sub.setStatus("ACTIVE");
            sub.setCurrentPeriodEnd(calculatePeriodEnd(newPlan.getBillingInterval()));
            subscriptionRepository.save(sub);

            resendEmailService.sendBillingAlert(appId, customer.getEmail(), customer.getEmail(), BigDecimal.ZERO,
                    "system_credit", "ACTIVE", null, null);
        }

        appRepository.findById(appId).ifPresent(app -> {
            triggerMerchantCallback(app.getWebhookUrl(), sub.getId(), appId, sub.getCustomerId(), sub.getPlanId(),
                    null, "ACTIVE", "plan_changed");
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
        Map<String, String> checkoutResult = nombaClientService.createCheckoutOrder(
                orderReference,
                amountKobo,
                customer.getEmail(),
                nombaCallbackUrl);

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
                    throw new IllegalStateException(
                            "At least one plan must exist in this app workspace to initialize card update.");
                }
            }

            Subscription sub = Subscription.builder()
                    .appId(appId)
                    .customerId(customer.getId())
                    .planId(planId)
                    .status("PENDING")
                    .nombaReference(orderReference)
                    .checkoutUrl(checkoutResult.get("checkoutLink"))
                    .mode(com.yourara.arafi.security.RequestContext.getMode() != null
                            ? com.yourara.arafi.security.RequestContext.getMode()
                            : "test")
                    .redirectUrl(redirectUrl)
                    .build();
            try {
                sub.setId(UUID.fromString(orderReference));
            } catch (Exception e) {
                // Ignore
            }
            subscriptionRepository.save(sub);

            return checkoutResult.get("checkoutLink");
        } else {
            throw new IllegalStateException(
                    "Failed to create tokenization checkout order: " + checkoutResult.get("message"));
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
                .discountAmountKobo(sub.getDiscountAmountKobo())
                .appliedCouponCode(sub.getAppliedCouponCode())
                .gracePeriodStart(sub.getGracePeriodStart())
                .retryCount(sub.getRetryCount())
                .build();
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifySubscriptionPayment(UUID appId, UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found."));

        if (!subscription.getAppId().equals(appId)) {
            throw new IllegalArgumentException("Unauthorized subscription context.");
        }

        if ("ACTIVE".equalsIgnoreCase(subscription.getStatus())) {
            return Map.of(
                    "success", true,
                    "status", "ACTIVE",
                    "message", "Subscription is already active.");
        }

        // nombaReference is the orderReference we passed to Nomba when creating the
        // checkout order
        String orderReference = subscription.getNombaReference();
        System.out.println(
                "[VerifyPayment] Querying Nomba for subscription=" + subscriptionId + " orderRef=" + orderReference);

        // Use /v1/transactions/accounts/single — works in BOTH sandbox and production
        // (unlike /v1/checkout/transaction which is production-only)
        Map<String, Object> txResponse = nombaClientService.fetchTransactionByOrderReference(orderReference);

        if (txResponse == null || !"00".equals(txResponse.get("code"))) {
            String description = txResponse != null && txResponse.get("description") != null
                    ? txResponse.get("description").toString()
                    : "No response from Nomba";
            System.out.println("[VerifyPayment] Transaction not found or API error: " + description);
            return Map.of(
                    "success", false,
                    "status", "PENDING",
                    "message", "Transaction not found on Nomba: " + description,
                    "nombaResponse", txResponse != null ? txResponse : Map.of());
        }

        Object dataObj = txResponse.get("data");
        if (!(dataObj instanceof Map)) {
            return Map.of(
                    "success", false,
                    "status", "PENDING",
                    "message", "Unexpected response structure from Nomba.",
                    "nombaResponse", txResponse);
        }

        Map<String, Object> data = (Map<String, Object>) dataObj;
        String txStatus = data.get("status") != null ? data.get("status").toString() : "UNKNOWN";
        System.out.println("[VerifyPayment] Nomba transaction status: " + txStatus);

        if (!"SUCCESS".equalsIgnoreCase(txStatus)) {
            return Map.of(
                    "success", false,
                    "status", "PENDING",
                    "message", "Payment not yet successful on Nomba. Current status: " + txStatus,
                    "nombaResponse", txResponse);
        }

        // Payment confirmed as SUCCESS — extract amount (Nomba returns NGN decimal,
        // e.g. "202.8")
        BigDecimal amount = BigDecimal.ZERO;
        if (data.get("amount") != null) {
            try {
                amount = new BigDecimal(data.get("amount").toString());
            } catch (NumberFormatException e) {
                System.err.println("[VerifyPayment] Could not parse amount: " + data.get("amount"));
            }
        }

        // Use the Nomba transaction ID from the response as our ledger reference
        String transactionId = data.get("id") != null ? data.get("id").toString() : orderReference;

        // NOTE: /v1/transactions/accounts/single does NOT return tokenKey.
        // tokenKey will be delivered by the async payment_success webhook and stored
        // then.
        // Passing null here is intentional — processCardPaymentSuccess guards against
        // overwriting an existing key.
        System.out.println("[VerifyPayment] SUCCESS confirmed. Activating subscription. amount=" + amount
                + " transactionId=" + transactionId + " (tokenKey will arrive via webhook)");
        processCardPaymentSuccess(orderReference, null, amount, transactionId);

        return Map.of(
                "success", true,
                "status", "ACTIVE",
                "message",
                "Payment verified with Nomba. Subscription activated. Card token will be stored when webhook arrives.",
                "orderReference", orderReference,
                "transactionId", transactionId,
                "amount", amount.toPlainString(),
                "nombaResponse", txResponse);
    }

    /**
     * Public verification method — called by the checkout callback page (no auth
     * required).
     * Looks up the subscription by orderReference (the UUID we passed to Nomba),
     * verifies payment status with Nomba, activates if successful, fires the
     * merchant webhook,
     * and returns the redirect URL + app/plan details for the frontend to use.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyPublicSubscriptionPayment(String orderReference) {
        if (orderReference == null || orderReference.isBlank()) {
            return Map.of("success", false, "message", "Missing orderReference parameter.");
        }

        System.out.println("[PublicVerify] Looking up subscription for orderReference=" + orderReference);

        // The orderReference IS the subscription UUID (we set sub.id = orderReference
        // in createSubscription)
        Subscription subscription = null;
        try {
            UUID subId = UUID.fromString(orderReference);
            subscription = subscriptionRepository.findById(subId).orElse(null);
        } catch (IllegalArgumentException e) {
            // Not a valid UUID, try nombaReference lookup
        }
        if (subscription == null) {
            subscription = subscriptionRepository.findByNombaReference(orderReference).orElse(null);
        }

        if (subscription == null) {
            System.out.println("[PublicVerify] No subscription found for orderReference=" + orderReference);
            return Map.of("success", false, "message", "Subscription not found for this order reference.");
        }

        // Set request context so downstream methods (processCardPaymentSuccess) work
        // correctly
        com.yourara.arafi.security.RequestContext.setContext(subscription.getAppId(), subscription.getMode());

        // Resolve app and plan details for the frontend receipt
        App app = appRepository.findById(subscription.getAppId()).orElse(null);
        Plan plan = planRepository.findById(subscription.getPlanId()).orElse(null);
        String appName = app != null ? app.getName() : "Unknown App";
        String planName = plan != null ? plan.getName() : "Unknown Plan";
        long planAmountKobo = plan != null ? plan.getAmountKobo() : 0;
        String planAmount = BigDecimal.valueOf(planAmountKobo).divide(BigDecimal.valueOf(100)).toPlainString();

        // If already active, return immediately (idempotent)
        if ("ACTIVE".equalsIgnoreCase(subscription.getStatus())) {
            System.out.println("[PublicVerify] Subscription already ACTIVE.");
            // Resolve redirectUrl: subscription override > app default > null (fallback
            // receipt)
            String redirectUrl = subscription.getRedirectUrl();
            if ((redirectUrl == null || redirectUrl.isBlank()) && app != null) {
                redirectUrl = app.getRedirectUrl();
            }
            java.util.HashMap<String, Object> result = new java.util.HashMap<>();
            result.put("success", true);
            result.put("status", "ACTIVE");
            result.put("message", "Subscription is already active.");
            result.put("appName", appName);
            result.put("planName", planName);
            result.put("amount", planAmount);
            result.put("orderReference", orderReference);
            result.put("redirectUrl", redirectUrl); // may be null — frontend shows receipt
            return result;
        }

        // If the subscription is for a virtual bank account transfer, we do not query
        // the checkout order endpoint
        // (as it is not a card checkout order, and Nomba sandbox mock-responds SUCCESS
        // for order query).
        // Instead, we simply return the current DB status (which gets activated via
        // webhook or sandbox simulator).
        if (subscription.getVirtualAccountNumber() != null && !subscription.getVirtualAccountNumber().isBlank()) {
            java.util.HashMap<String, Object> result = new java.util.HashMap<>();
            result.put("success", false);
            result.put("status", subscription.getStatus());
            result.put("message", "Awaiting bank transfer credit verification.");
            result.put("appName", appName);
            result.put("planName", planName);
            result.put("amount", planAmount);
            result.put("orderReference", orderReference);
            result.put("redirectUrl", null);
            return result;
        }

        // Query Nomba for transaction status
        System.out.println("[PublicVerify] Querying Nomba for orderReference=" + orderReference);
        Map<String, Object> txResponse = nombaClientService.fetchTransactionByOrderReference(orderReference);

        if (txResponse == null || !"00".equals(txResponse.get("code"))) {
            String description = txResponse != null && txResponse.get("description") != null
                    ? txResponse.get("description").toString()
                    : "No response from Nomba";
            System.out.println("[PublicVerify] Nomba lookup failed: " + description);
            java.util.HashMap<String, Object> result = new java.util.HashMap<>();
            result.put("success", false);
            result.put("status", "PENDING");
            result.put("message", "Transaction not found on Nomba: " + description);
            result.put("appName", appName);
            result.put("planName", planName);
            result.put("amount", planAmount);
            result.put("orderReference", orderReference);
            result.put("redirectUrl", null);
            return result;
        }

        Object dataObj = txResponse.get("data");
        if (!(dataObj instanceof Map)) {
            java.util.HashMap<String, Object> result = new java.util.HashMap<>();
            result.put("success", false);
            result.put("status", "PENDING");
            result.put("message", "Unexpected response structure from Nomba.");
            result.put("appName", appName);
            result.put("redirectUrl", null);
            return result;
        }

        Map<String, Object> data = (Map<String, Object>) dataObj;
        String txStatus = data.get("status") != null ? data.get("status").toString() : "UNKNOWN";
        System.out.println("[PublicVerify] Nomba transaction status: " + txStatus);

        if (!"SUCCESS".equalsIgnoreCase(txStatus)) {
            java.util.HashMap<String, Object> result = new java.util.HashMap<>();
            result.put("success", false);
            result.put("status", "PENDING");
            result.put("message", "Payment not yet successful. Current status: " + txStatus);
            result.put("appName", appName);
            result.put("planName", planName);
            result.put("amount", planAmount);
            result.put("orderReference", orderReference);
            result.put("redirectUrl", null);
            return result;
        }

        // SUCCESS — extract amount and activate
        BigDecimal amount = BigDecimal.ZERO;
        if (data.get("amount") != null) {
            try {
                amount = new BigDecimal(data.get("amount").toString());
            } catch (NumberFormatException e) {
                System.err.println("[PublicVerify] Could not parse amount: " + data.get("amount"));
            }
        }
        String transactionId = data.get("id") != null ? data.get("id").toString() : orderReference;

        System.out.println(
                "[PublicVerify] SUCCESS. Activating subscription. amount=" + amount + " txId=" + transactionId);
        processCardPaymentSuccess(orderReference, null, amount, transactionId);

        // Resolve redirectUrl: subscription override > app default > null
        String redirectUrl = subscription.getRedirectUrl();
        if ((redirectUrl == null || redirectUrl.isBlank()) && app != null) {
            redirectUrl = app.getRedirectUrl();
        }

        java.util.HashMap<String, Object> result = new java.util.HashMap<>();
        result.put("success", true);
        result.put("status", "ACTIVE");
        result.put("message", "Payment verified. Subscription activated.");
        result.put("appName", appName);
        result.put("planName", planName);
        result.put("amount", amount.toPlainString());
        result.put("orderReference", orderReference);
        result.put("transactionId", transactionId);
        result.put("redirectUrl", redirectUrl); // null = frontend shows branded receipt
        return result;
    }

    /**
     * Simulates a bank transfer payment. Directly invokes
     * processVirtualAccountPayment with a mock transaction ID.
     */
    @Transactional
    public void simulateVirtualAccountTransfer(String virtualAccountNumber, BigDecimal amount) {
        String mockTxId = "sim_trsf_" + UUID.randomUUID().toString().substring(0, 15);
        processVirtualAccountPayment(virtualAccountNumber, amount, mockTxId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> publicGetSubscriptionDetails(UUID subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription context not found."));

        App app = appRepository.findById(sub.getAppId()).orElse(null);
        Plan plan = planRepository.findById(sub.getPlanId()).orElse(null);
        Customer customer = customerRepository.findById(sub.getCustomerId()).orElse(null);

        String appName = app != null ? app.getName() : "Unknown App";
        String planName = plan != null ? plan.getName() : "Unknown Plan";
        long planAmountKobo = plan != null ? plan.getAmountKobo() : 0;
        long discountAmountKobo = sub.getDiscountAmountKobo() != null ? sub.getDiscountAmountKobo() : 0;
        long finalAmountKobo = Math.max(0L, planAmountKobo - discountAmountKobo);
        String finalAmount = BigDecimal.valueOf(finalAmountKobo).divide(BigDecimal.valueOf(100)).toPlainString();
        String baseAmount = BigDecimal.valueOf(planAmountKobo).divide(BigDecimal.valueOf(100)).toPlainString();

        java.util.HashMap<String, Object> result = new java.util.HashMap<>();
        result.put("id", sub.getId().toString());
        result.put("appName", appName);
        result.put("planName", planName);
        result.put("baseAmount", baseAmount);
        result.put("finalAmount", finalAmount);
        result.put("discountAmount",
                BigDecimal.valueOf(discountAmountKobo).divide(BigDecimal.valueOf(100)).toPlainString());
        result.put("appliedCouponCode", sub.getAppliedCouponCode());
        result.put("interval", plan != null ? plan.getBillingInterval() : "monthly");
        result.put("customerEmail", customer != null ? customer.getEmail() : "N/A");
        result.put("virtualAccountNumber", sub.getVirtualAccountNumber());
        result.put("status", sub.getStatus());
        result.put("mode", sub.getMode());
        return result;
    }

    @Transactional
    public Map<String, String> publicGenerateCardCheckoutUrl(UUID subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription context not found."));

        Customer customer = customerRepository.findById(sub.getCustomerId())
                .orElseThrow(() -> new IllegalStateException("Customer profile not found."));

        Plan plan = planRepository.findById(sub.getPlanId())
                .orElseThrow(() -> new IllegalStateException("Billing plan details not found."));

        long planAmountKobo = plan.getAmountKobo();
        long discountAmountKobo = sub.getDiscountAmountKobo() != null ? sub.getDiscountAmountKobo() : 0;
        long finalAmountKobo = Math.max(0L, planAmountKobo - discountAmountKobo);

        com.yourara.arafi.security.RequestContext.setContext(sub.getAppId(), sub.getMode());

        try {
            Map<String, String> checkoutResult = nombaClientService.createCheckoutOrder(
                    sub.getId().toString(),
                    finalAmountKobo,
                    customer.getEmail(),
                    nombaCallbackUrl,
                    List.of("Card"));

            if ("success".equals(checkoutResult.get("status"))) {
                sub.setNombaReference(sub.getId().toString());
                sub.setCheckoutUrl(checkoutResult.get("checkoutLink"));
                subscriptionRepository.save(sub);
                return Map.of("checkoutLink", checkoutResult.get("checkoutLink"));
            } else {
                throw new IllegalStateException("Nomba checkout processing error: " + checkoutResult.get("message"));
            }
        } finally {
            com.yourara.arafi.security.RequestContext.clear();
        }
    }

    @Transactional
    public Map<String, String> publicProvisionBankTransfer(UUID subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalArgumentException("Subscription context not found."));

        Customer customer = customerRepository.findById(sub.getCustomerId())
                .orElseThrow(() -> new IllegalStateException("Customer profile not found."));

        Plan plan = planRepository.findById(sub.getPlanId())
                .orElseThrow(() -> new IllegalStateException("Billing plan details not found."));

        long planAmountKobo = plan.getAmountKobo();
        long discountAmountKobo = sub.getDiscountAmountKobo() != null ? sub.getDiscountAmountKobo() : 0;
        long finalAmountKobo = Math.max(0L, planAmountKobo - discountAmountKobo);
        BigDecimal amountDecimal = BigDecimal.valueOf(finalAmountKobo).divide(BigDecimal.valueOf(100));

        com.yourara.arafi.security.RequestContext.setContext(sub.getAppId(), sub.getMode());

        try {
            String virtualAccountNumber = customer.getVirtualAccountNumber();
            String bankName = "Nomba Bank";

            if (virtualAccountNumber == null || virtualAccountNumber.isBlank()) {
                String accountRef = "arafi_vban_" + customer.getId().toString();
                String accountName = "ARAFI * " + customer.getEmail();
                Map<String, String> accountDetails = nombaClientService.createVirtualAccount(accountRef, accountName,
                        amountDecimal);

                if ("success".equals(accountDetails.get("status"))) {
                    virtualAccountNumber = accountDetails.get("bankAccountNumber");
                    bankName = accountDetails.get("bankName");

                    customer.setVirtualAccountNumber(virtualAccountNumber);
                    customerRepository.save(customer);
                } else {
                    throw new IllegalStateException("Nomba virtual account error: " + accountDetails.get("message"));
                }
            } else {
                log.info(
                        "[SubscriptionService] Reusing existing virtual account number from database: {} for customer: {} in public checkout.",
                        virtualAccountNumber, customer.getEmail());
            }

            sub.setVirtualAccountNumber(virtualAccountNumber);
            subscriptionRepository.save(sub);

            resendEmailService.sendBillingAlert(sub.getAppId(), customer.getEmail(), customer.getEmail(),
                    amountDecimal, "bank_transfer", "PENDING", bankName, virtualAccountNumber);

            return Map.of(
                    "bankAccountNumber", virtualAccountNumber,
                    "bankName", bankName,
                    "bankAccountName", "ARAFI * " + customer.getEmail());
        } finally {
            com.yourara.arafi.security.RequestContext.clear();
        }
    }
}
