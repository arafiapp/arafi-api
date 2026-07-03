package com.yourara.arafi.repository;

import com.yourara.arafi.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    Optional<Subscription> findByIdAndAppId(UUID id, UUID appId);
    List<Subscription> findByAppId(UUID appId);
}
