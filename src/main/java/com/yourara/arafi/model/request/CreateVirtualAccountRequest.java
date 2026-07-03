package com.yourara.arafi.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateVirtualAccountRequest {

    @JsonProperty("customer_ref")
    private String customerRef;

    @JsonProperty("account_name")
    private String accountName;
}
