package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/** RestTemplate의 DEBUG 요청 로그에도 자격증명 원문이 출력되지 않는 요청입니다. */
@Getter
public final class KiwoomAccessTokenRequest {
    @JsonProperty("grant_type")
    private final String grantType = "client_credentials";
    @JsonProperty("appkey")
    private final String appKey;
    @JsonProperty("secretkey")
    private final String appSecret;

    public KiwoomAccessTokenRequest(String appKey, String appSecret) {
        this.appKey = appKey;
        this.appSecret = appSecret;
    }
}
