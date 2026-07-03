package com.yourara.arafi.service;

import com.yourara.arafi.model.EmailTemplate;
import com.yourara.arafi.repository.EmailTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ResendEmailService {

    private final EmailTemplateRepository emailTemplateRepository;
    private final RestTemplate restTemplate;

    @Value("${resend.api.key:mock_resend_api_key}")
    private String resendApiKey;

    @Value("${resend.from.email:onboarding@resend.dev}")
    private String fromEmail;

    @Async
    public void sendBillingAlert(UUID appId, String customerEmail, String customerName, BigDecimal amount, String paymentMethod, String status) {
        String subject = "Subscription Billing Alert";
        String body = "Dear customer, your subscription is currently " + status + ". Amount: " + amount + " NGN via " + paymentMethod + ".";

        // Look up the customized template for the target appId. If missing, fallback cleanly to a standard system default text layout.
        EmailTemplate template = emailTemplateRepository.findByAppId(appId).orElse(null);
        if (template != null) {
            subject = template.getEmailSubject();
            body = template.getEmailBodyTemplate();
        }

        // Dynamically replace strings ({{customer_name}}, {{amount}}, {{payment_method}}) with active transactional run parameters.
        if (customerName == null) {
            customerName = customerEmail;
        }
        String formattedAmount = String.format("%.2f", amount.doubleValue());
        
        subject = subject.replace("{{customer_name}}", customerName)
                         .replace("{{amount}}", formattedAmount)
                         .replace("{{payment_method}}", paymentMethod);
                         
        body = body.replace("{{customer_name}}", customerName)
                   .replace("{{amount}}", formattedAmount)
                   .replace("{{payment_method}}", paymentMethod);

        // Issue a standard outbound POST to https://api.resend.com/emails authorized using a Bearer <resend_api_key> header parameter context.
        String url = "https://api.resend.com/emails";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + resendApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> payload = Map.of(
            "from", "Arafi Billing <" + fromEmail + ">",
            "to", customerEmail,
            "subject", subject,
            "html", body
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            restTemplate.postForEntity(url, entity, String.class);
            System.out.println("Billing alert email sent successfully to " + customerEmail + " via Resend.");
        } catch (Exception e) {
            System.err.println("Failed to send billing alert email via Resend: " + e.getMessage());
        }
    }
}
