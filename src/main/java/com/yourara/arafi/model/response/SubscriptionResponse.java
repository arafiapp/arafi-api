package com.yourara.arafi.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionResponse {

    private UUID id;

    @JsonProperty("app_id")
    private UUID appId;

    @JsonProperty("customer_id")
    private UUID customerId;

    @JsonProperty("plan_id")
    private UUID planId;

    private String status;

    @JsonProperty("current_period_end")
    private Instant currentPeriodEnd;

    @JsonProperty("nomba_token_key")
    private String nombaTokenKey;

    @JsonProperty("virtual_account_number")
    private String virtualAccountNumber;

    @JsonProperty("nomba_reference")
    private String nombaReference;

    @JsonProperty("created_at")
    private Instant createdAt;
}
