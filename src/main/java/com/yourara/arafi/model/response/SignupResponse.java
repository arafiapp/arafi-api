package com.yourara.arafi.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SignupResponse {

    private String message;

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("app_count")
    private long appCount;
}
