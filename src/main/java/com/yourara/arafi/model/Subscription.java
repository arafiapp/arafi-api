package com.yourara.arafi.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "subscriptions", schema = "arafi")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Subscription {

    @Id
    private UUID id;

    @Column(name = "app_id", nullable = false)
    private UUID appId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(nullable = false)
    private String status; // "PENDING", "ACTIVE", "EXPIRED"

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "nomba_token_key")
    private String nombaTokenKey; // Stored from tokenized card checkout webhooks

    @Column(name = "virtual_account_number")
    private String virtualAccountNumber; // Bound static transfer fallback account

    @Column(name = "nomba_reference")
    private String nombaReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }
}
