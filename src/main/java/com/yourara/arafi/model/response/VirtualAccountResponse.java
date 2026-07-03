package com.yourara.arafi.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VirtualAccountResponse {

    private UUID id;

    @JsonProperty("bank_account_number")
    private String bankAccountNumber;

    @JsonProperty("bank_name")
    private String bankName;

    private String currency;

    @JsonProperty("customer_ref")
    private String customerRef;

    private String mode;
}
