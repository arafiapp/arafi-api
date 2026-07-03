package com.yourara.arafi.repository;

import com.yourara.arafi.model.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface PlanRepository extends JpaRepository<Plan, UUID> {
    Optional<Plan> findByIdAndAppId(UUID id, UUID appId);
    List<Plan> findByAppId(UUID appId);
}
