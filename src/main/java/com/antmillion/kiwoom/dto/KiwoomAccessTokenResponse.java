package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/** 원격 메시지와 토큰을 로그에 출력하지 않도록 toString을 생성하지 않습니다. */
@Getter
@Setter
public class KiwoomAccessTokenResponse {
    private String token;
    @JsonProperty("token_type")
    private String tokenType;
    @JsonProperty("expires_dt")
    private String expiresDt;
    @JsonProperty("return_code")
    private Integer returnCode;
}
