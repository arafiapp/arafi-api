package com.yourara.arafi.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreatePlanRequest {

    private String name;

    @JsonProperty("amount_kobo")
    private Long amountKobo;

    private String interval; // e.g. monthly, yearly, one_time

    @JsonProperty("grace_period_days")
    private Integer gracePeriodDays;
}
