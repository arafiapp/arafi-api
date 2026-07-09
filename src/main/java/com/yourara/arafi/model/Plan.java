package com.yourara.arafi.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "plans", schema = "arafi")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan {

    @Id
    private UUID id;

    @Column(name = "app_id", nullable = false)
    private UUID appId;

    @Column(nullable = false)
    private String name;

    @Column(name = "amount_kobo", nullable = false)
    private Long amountKobo;

    @Column(name = "billing_interval", nullable = false)
    private String billingInterval; // e.g. monthly, yearly, one_time

    @Column(name = "grace_period_days")
    private Integer gracePeriodDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }
}
