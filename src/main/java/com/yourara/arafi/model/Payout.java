package com.yourara.arafi.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payouts", schema = "arafi")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class Payout {
    @Id
    private UUID id;

    @Column(name = "app_id", nullable = false)
    private UUID appId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "bank_account_number", nullable = false)
    private String bankAccountNumber;

    @Column(name = "bank_code", nullable = false)
    private String bankCode;

    @Column(name = "bank_name")
    private String bankName;

    @Column(nullable = false)
    private String status; // "PENDING", "SUCCESS", "FAILED"

    @Column(nullable = false)
    private String mode; // "test" or "live"

    @Column(name = "nomba_transfer_id")
    private String nombaTransferId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        if (this.status == null) this.status = "PENDING";
    }
}
