package com.yourara.arafi.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys", schema = "arafi")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class ApiKey {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_id", nullable = false)
    private com.yourara.arafi.model.App app;

    @Column(name = "key_prefix", nullable = false)
    private String keyPrefix; // e.g., 'arafi_test_'

    @Column(name = "key_hash", nullable = false)
    private String keyHash; // BCrypt hash of complete token string

    @Column(nullable = false)
    private String mode; // 'test' or 'live'

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }
}