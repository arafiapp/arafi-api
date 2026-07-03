package com.yourara.arafi.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletBalanceResponse {

    @JsonProperty("app_id")
    private UUID appId;

    @JsonProperty("available_balance")
    private BigDecimal availableBalance;

    private String currency;

    @JsonProperty("environment_mode")
    private String environmentMode;
}
