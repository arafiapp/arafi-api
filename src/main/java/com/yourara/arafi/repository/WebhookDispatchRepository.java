package com.yourara.arafi.repository;

import com.yourara.arafi.model.WebhookDispatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WebhookDispatchRepository extends JpaRepository<WebhookDispatch, UUID> {
    List<WebhookDispatch> findByStatusAndNextAttemptAtBeforeAndAttemptsLessThan(
            String status, 
            Instant nextAttemptAt, 
            Integer maxAttempts
    );
}
