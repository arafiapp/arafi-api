package com.yourara.arafi.repository;

import com.yourara.arafi.model.Payout;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {
    List<Payout> findByAppId(UUID appId);
    List<Payout> findByStatus(String status);
}
