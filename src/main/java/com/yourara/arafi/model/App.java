package com.yourara.arafi.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "apps", schema = "arafi")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class App {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status; // 'active', 'suspended'

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Column(name = "webhook_secret")
    private String webhookSecret;

    @Column(name = "payout_bank_account")
    private String payoutBankAccountNumber;

    @Column(name = "payout_bank_code")
    private String payoutBankCode;

    @Column(name = "payout_bank_name")
    private String payoutBankName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.status = "active";
        this.createdAt = Instant.now();
        if (this.webhookSecret == null) {
            this.webhookSecret = "arafi_whsec_" + UUID.randomUUID().toString().replace("-", "");
        }
    }
}