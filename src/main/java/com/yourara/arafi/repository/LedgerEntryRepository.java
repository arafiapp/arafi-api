package com.yourara.arafi.repository;

import com.yourara.arafi.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    // Aggregates every single historic delta log line to derive an absolute real-time credit balance
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM LedgerEntry l WHERE l.appId = :appId")
    BigDecimal computeBalanceForApp(@Param("appId") UUID appId);
}