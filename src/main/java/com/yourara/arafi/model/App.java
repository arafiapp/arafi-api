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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.status = "active";
        this.createdAt = Instant.now();
    }
}