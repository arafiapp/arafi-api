package com.yourara.arafi;

import com.yourara.arafi.model.*;
import com.yourara.arafi.model.request.CreateSubscriptionRequest;
import com.yourara.arafi.model.response.SubscriptionResponse;
import com.yourara.arafi.repository.*;
import com.yourara.arafi.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ArafiApiApplicationTests {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private WebhookRepository webhookRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void testFullPaymentFlows() {
        // 1. Create a User (developer)
        User user = User.builder()
                .email("dev_" + UUID.randomUUID() + "@example.com")
                .passwordHash("hashed_password")
                .build();
        userRepository.save(user);

        // 2. Create an App Workspace
        App app = App.builder()
                .user(user)
                .name("Arafi Test App")
                .webhookUrl("http://localhost:9999/merchant-callback")
                .status("active")
                .build();
        appRepository.save(app);

        // 3. Create a Plan
        Plan plan = Plan.builder()
                .appId(app.getId())
                .name("Standard Plan")
                .amountKobo(500000L) // 5000.00 NGN
                .billingInterval("monthly")
                .build();
        planRepository.save(plan);

        // 4. Create a Customer (subscriber)
        Customer customer = Customer.builder()
                .appId(app.getId())
                .email("subscriber_" + UUID.randomUUID() + "@example.com")
                .externalRef("ext_sub_001")
                .build();
        customerRepository.save(customer);

        // ==========================================
        // FLOW A: FIRST-TIME CARD CHECKOUT
        // ==========================================
        CreateSubscriptionRequest cardReq = new CreateSubscriptionRequest();
        cardReq.setCustomerId(customer.getId());
        cardReq.setPlanId(plan.getId());
        cardReq.setPaymentMethod("CARD");

        SubscriptionResponse cardResp = subscriptionService.createSubscription(app.getId(), cardReq);
        assertNotNull(cardResp);
        assertEquals("PENDING", cardResp.getStatus());
        assertNotNull(cardResp.getCheckoutUrl());
        assertNotNull(cardResp.getNombaReference());

        // Verify checkout URL structure
        assertTrue(cardResp.getCheckoutUrl().startsWith("http"));

        // Simulate incoming card checkout success webhook (payment_success)
        String orderRef = cardResp.getNombaReference();
        String tokenKey = "nbr_tok_" + UUID.randomUUID().toString().substring(0, 10);
        
        Map<String, Object> cardPayload = Map.of(
            "requestId", "req_" + UUID.randomUUID(),
            "event_type", "payment_success",
            "data", Map.of(
                "orderReference", orderRef,
                "tokenKey", tokenKey,
                "amount", 5000.00
            )
        );

        WebhookEvent cardWebhook = WebhookEvent.builder()
                .nombaEventId("req_card_" + UUID.randomUUID().toString().substring(0,8))
                .eventType("payment_success")
                .rawPayload(cardPayload)
                .isSignatureVerified(true)
                .processingStatus("received")
                .build();
        webhookRepository.save(cardWebhook);

        // Process webhooks asynchronously (runs the webhook processor state machine)
        subscriptionService.processReceivedWebhooks();

        // Verify CARD subscription is now ACTIVE and vaulted the card details
        Subscription cardSub = subscriptionRepository.findById(cardResp.getId()).orElse(null);
        assertNotNull(cardSub);
        assertEquals("ACTIVE", cardSub.getStatus());
        assertEquals(tokenKey, cardSub.getNombaTokenKey());
        assertNotNull(cardSub.getCurrentPeriodEnd());

        // Verify Customer record also received the vaulted nombaTokenKey
        Customer updatedCustomer = customerRepository.findById(customer.getId()).orElse(null);
        assertNotNull(updatedCustomer);
        assertEquals(tokenKey, updatedCustomer.getNombaTokenKey());

        // Verify Ledger Entry credit was logged
        BigDecimal balance = ledgerEntryRepository.computeBalanceForApp(app.getId());
        assertEquals(0, new BigDecimal("5000.00").compareTo(balance));

        // ==========================================
        // FLOW B: FIRST-TIME BANK TRANSFER VBAN
        // ==========================================
        CreateSubscriptionRequest bankReq = new CreateSubscriptionRequest();
        bankReq.setCustomerId(customer.getId());
        bankReq.setPlanId(plan.getId());
        bankReq.setPaymentMethod("BANK_TRANSFER");

        SubscriptionResponse bankResp = subscriptionService.createSubscription(app.getId(), bankReq);
        assertNotNull(bankResp);
        assertEquals("PENDING", bankResp.getStatus());
        assertNotNull(bankResp.getVirtualAccountNumber());

        // Verify the customer record now has the virtual account number saved permanently
        Customer customerWithVban = customerRepository.findById(customer.getId()).orElse(null);
        assertNotNull(customerWithVban);
        assertEquals(bankResp.getVirtualAccountNumber(), customerWithVban.getVirtualAccountNumber());

        // Simulate incoming bank transfer credit webhook (virtual_account_payment)
        String vban = bankResp.getVirtualAccountNumber();
        Map<String, Object> bankPayload = Map.of(
            "requestId", "req_bank_" + UUID.randomUUID(),
            "event_type", "virtual_account_payment",
            "data", Map.of(
                "bankAccountNumber", vban,
                "amount", 5000.00
            )
        );

        WebhookEvent bankWebhook = WebhookEvent.builder()
                .nombaEventId("req_va_" + UUID.randomUUID().toString().substring(0,8))
                .eventType("virtual_account_payment")
                .rawPayload(bankPayload)
                .isSignatureVerified(true)
                .processingStatus("received")
                .build();
        webhookRepository.save(bankWebhook);

        // Process webhooks
        subscriptionService.processReceivedWebhooks();

        // Verify BANK subscription is now ACTIVE
        Subscription bankSub = subscriptionRepository.findById(bankResp.getId()).orElse(null);
        assertNotNull(bankSub);
        assertEquals("ACTIVE", bankSub.getStatus());
        assertNotNull(bankSub.getCurrentPeriodEnd());

        // Verify Ledger Entry credit was logged for this bank account, total balance is now 10000.00
        BigDecimal bankBalance = ledgerEntryRepository.computeBalanceForApp(app.getId());
        assertEquals(0, new BigDecimal("10000.00").compareTo(bankBalance));

        // ==========================================
        // FLOW C: CRON RENEWALS & EXPIRATION
        // ==========================================
        // 1. Manually expire the BANK TRANSFER subscription in the database
        bankSub.setCurrentPeriodEnd(Instant.now().minusSeconds(10));
        subscriptionRepository.save(bankSub);

        // 2. Manually expire the CARD subscription in the database
        cardSub.setCurrentPeriodEnd(Instant.now().minusSeconds(10));
        subscriptionRepository.save(cardSub);

        // Run subscription renewals cron worker
        subscriptionService.processSubscriptionRenewals();

        // Verify Bank Transfer subscription status transitioned to EXPIRED (requiring manual bank transfer reminder)
        Subscription renewedBankSub = subscriptionRepository.findById(bankSub.getId()).orElse(null);
        assertNotNull(renewedBankSub);
        assertEquals("EXPIRED", renewedBankSub.getStatus());

        // Verify Card subscription status transitioned to EXPIRED (because no sandbox credentials exist to charge it successfully)
        Subscription renewedCardSub = subscriptionRepository.findById(cardSub.getId()).orElse(null);
        assertNotNull(renewedCardSub);
        assertEquals("EXPIRED", renewedCardSub.getStatus());
        assertNotNull(renewedCardSub.getCheckoutUrl()); // Verify a recovery checkout URL is provided in the Card failure email info!
    }
}
