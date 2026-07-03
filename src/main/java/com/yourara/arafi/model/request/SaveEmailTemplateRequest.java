package com.yourara.arafi.model.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SaveEmailTemplateRequest {
    private String subject;
    private String body;
}
