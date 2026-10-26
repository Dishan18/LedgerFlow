package com.ledgerflow.api.merchant;

import com.ledgerflow.api.support.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "merchant")
public class Merchant extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    /** Platform fee in basis points (200 = 2%). */
    @Column(name = "fee_bps", nullable = false)
    private int feeBps;

    /** Where payment events are POSTed. Null means the merchant doesn't want webhooks. */
    @Column(name = "webhook_url", length = 2048)
    private String webhookUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Merchant() {
        // for JPA
    }

    public Merchant(UUID id, String name, int feeBps) {
        this(id, name, feeBps, null);
    }

    public Merchant(UUID id, String name, int feeBps, String webhookUrl) {
        this.id = id;
        this.name = name;
        this.feeBps = feeBps;
        this.webhookUrl = webhookUrl;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getFeeBps() {
        return feeBps;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }
}
