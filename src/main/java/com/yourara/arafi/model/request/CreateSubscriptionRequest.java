package com.yourara.arafi.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

@Getter
@Setter
public class CreateSubscriptionRequest {

    @JsonProperty("customer_id")
    private UUID customerId;

    @JsonProperty("plan_id")
    private UUID planId;
}
