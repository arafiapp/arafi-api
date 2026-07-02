package com.yourara.arafi.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries", schema = "arafi")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class LedgerEntry {
    @Id
    private UUID id;

    @Column(name = "app_id", nullable = false)
    private UUID appId;

    @Column(name = "account_number", nullable = false)
    private String bankAccountNumber;

    @Column(nullable = false)
    private BigDecimal amount; // Positives represent Credit deposits, Negatives reflect debits

    @Column(name = "entry_type", nullable = false)
    private String entryType; // 'CREDIT' or 'DEBIT'

    @Column(name = "webhook_event_id")
    private String webhookEventId; // Maps back to the raw source data point log

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }
}