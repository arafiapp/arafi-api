package com.yourara.arafi.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateWorkspaceResponse {

    @JsonProperty("app_id")
    private UUID appId;

    @JsonProperty("app_name")
    private String appName;

    private String status;

    @JsonProperty("sandbox_key")
    private String sandboxKey;

    @JsonProperty("live_key")
    private String liveKey;

    @JsonProperty("webhook_url")
    private String webhookUrl;

    @JsonProperty("redirect_url")
    private String redirectUrl;

    @JsonProperty("webhook_secret")
    private String webhookSecret;
}
