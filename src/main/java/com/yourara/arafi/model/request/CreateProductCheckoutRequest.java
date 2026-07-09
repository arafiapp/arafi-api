package com.yourara.arafi.model.request;

import lombok.Data;

@Data
public class CreateProductCheckoutRequest {
    private String customerEmail;
    private String customerName;
    private String paymentMethod; // CARD or BANK_TRANSFER
    private String redirectUrl;
}
