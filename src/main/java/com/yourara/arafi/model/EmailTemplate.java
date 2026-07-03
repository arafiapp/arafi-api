package com.yourara.arafi.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_templates", schema = "arafi")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class EmailTemplate {
    @Id
    private UUID id;

    @Column(name = "app_id", nullable = false, unique = true)
    private UUID appId;

    @Column(name = "email_subject", nullable = false)
    private String emailSubject;

    @Column(name = "email_body_template", nullable = false, columnDefinition = "TEXT")
    private String emailBodyTemplate; // Supports template tags: {{customer_name}}, {{amount}}, {{payment_method}}

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) this.id = UUID.randomUUID();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
