package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/** ka10064의 전체 시장·KRX 종목·주 단위 순매수 조회입니다. */
@Getter
public final class KiwoomInvestorFlowRequest {
    @JsonProperty("mrkt_tp")
    private final String market = "000";
    @JsonProperty("amt_qty_tp")
    private final String amountQuantity = "2";
    @JsonProperty("trde_tp")
    private final String trade = "0";
    @JsonProperty("stk_cd")
    private final String stockCode;

    public KiwoomInvestorFlowRequest(String stockCode) {
        this.stockCode = stockCode;
    }
}
