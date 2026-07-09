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
public class CustomerResponse {

    private UUID id;

    @JsonProperty("app_id")
    private UUID appId;

    private String email;

    private String name;

    @JsonProperty("external_ref")
    private String externalRef;

    @JsonProperty("created_at")
    private Instant createdAt;
}
