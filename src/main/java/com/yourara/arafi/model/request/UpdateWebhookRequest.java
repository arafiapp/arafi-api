package com.yourara.arafi.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class UpdateWebhookRequest {
    @JsonProperty("webhook_url")
    private String webhookUrl;

    @JsonProperty("redirect_url")
    private String redirectUrl;

    private boolean rotateSecret;
}
