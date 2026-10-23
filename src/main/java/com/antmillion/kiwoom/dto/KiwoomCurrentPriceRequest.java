package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/** ka10001의 KRX 종목 단건 요청입니다. */
@Getter
public final class KiwoomCurrentPriceRequest {
    @JsonProperty("stk_cd")
    private final String stockCode;

    public KiwoomCurrentPriceRequest(String stockCode) {
        this.stockCode = stockCode;
    }
}
