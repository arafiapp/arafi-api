package com.yourara.arafi.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "coupons", schema = "arafi")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Coupon {

    @Id
    private UUID id;

    @Column(name = "app_id", nullable = false)
    private UUID appId;

    @Column(nullable = false)
    private String code; // e.g. SUMMER50

    @Column(name = "discount_amount_kobo", nullable = false)
    private Long discountAmountKobo;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        if (this.active == null) this.active = true;
    }
}
