package com.yourara.arafi.model.request;

import lombok.Data;

@Data
public class CreateProductRequest {
    private String name;
    private String sku;
    private Long priceKobo;
    private String description;
}
