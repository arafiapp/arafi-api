package com.yourara.arafi.repository;

import com.yourara.arafi.model.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface WebhookRepository extends JpaRepository<WebhookEvent, UUID> {
    List<WebhookEvent> findByProcessingStatus(String processingStatus);
    List<WebhookEvent> findByProcessingStatusAndIsSignatureVerifiedTrue(String processingStatus);
}
