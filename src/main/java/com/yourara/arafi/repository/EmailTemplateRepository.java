package com.yourara.arafi.repository;

import com.yourara.arafi.model.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, UUID> {
    Optional<EmailTemplate> findByAppId(UUID appId);
}
