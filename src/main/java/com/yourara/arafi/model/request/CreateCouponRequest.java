package com.yourara.arafi.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateCouponRequest {
    private String code;

    @JsonProperty("discount_amount_kobo")
    private Long discountAmountKobo;
}
