package com.yourara.arafi.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers", schema = "arafi")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    private UUID id;

    @Column(name = "app_id", nullable = false)
    private UUID appId;

    @Column(nullable = false)
    private String email;

    @Column(name = "name")
    private String name;

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "nomba_token_key")
    private String nombaTokenKey;

    @Column(name = "virtual_account_number")
    private String virtualAccountNumber;

    @Column(name = "mode", nullable = false)
    private String mode; // "test" or "live"

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
    }
}
