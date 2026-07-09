package com.yourara.arafi.repository;

import com.yourara.arafi.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByIdAndAppId(UUID id, UUID appId);
    List<Customer> findByAppId(UUID appId);
    Optional<Customer> findByAppIdAndEmail(UUID appId, String email);
}
