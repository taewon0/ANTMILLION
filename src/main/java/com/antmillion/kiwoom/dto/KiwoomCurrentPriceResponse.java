package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/** 현재가 계약에 필요한 필드만 읽으며 원격 메시지와 toString을 노출하지 않습니다. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class KiwoomCurrentPriceResponse {
    @JsonProperty("stk_cd")
    private String stockCode;
    @JsonProperty("cur_prc")
    private String currentPrice;
    @JsonProperty("flu_rt")
    private String changeRate;
    @JsonProperty("return_code")
    private Integer returnCode;
}
