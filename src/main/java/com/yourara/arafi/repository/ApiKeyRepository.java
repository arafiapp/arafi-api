package com.yourara.arafi.repository;

import com.yourara.arafi.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    List<ApiKey> findByKeyPrefixAndRevokedAtIsNull(String keyPrefix);
}
