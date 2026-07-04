package com.yourara.arafi.model.request;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class PayoutRequest {
    private BigDecimal amount;
    private String bankAccountNumber;
    private String bankCode;
    private String bankName;
}
