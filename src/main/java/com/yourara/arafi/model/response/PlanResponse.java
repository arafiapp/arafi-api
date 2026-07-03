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
public class PlanResponse {

    private UUID id;

    @JsonProperty("app_id")
    private UUID appId;

    private String name;

    @JsonProperty("amount_kobo")
    private Long amountKobo;

    private String interval;

    @JsonProperty("created_at")
    private Instant createdAt;
}
