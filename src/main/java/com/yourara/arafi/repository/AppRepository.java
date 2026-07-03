package com.yourara.arafi.repository;

import com.yourara.arafi.model.App;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AppRepository extends JpaRepository<App, UUID> {
    long countByUserId(UUID userId);
}
