package com.yourara.arafi.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yourara.arafi.model.PaymentMethod;
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

    @JsonProperty("payment_method")
    private String paymentMethod;

    @JsonProperty("redirect_url")
    private String redirectUrl;

    @JsonProperty("coupon_code")
    private String couponCode;
}
