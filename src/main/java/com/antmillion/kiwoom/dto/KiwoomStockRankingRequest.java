package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/** ka10030의 전체 KRX 조회입니다. 거래량순에서만 ETF·ETN을 제외합니다. */
@Getter
public final class KiwoomStockRankingRequest {
    @JsonProperty("mrkt_tp")
    private final String market = "000";
    @JsonProperty("sort_tp")
    private final String sort;
    @JsonProperty("mang_stk_incls")
    private final String includedClasses;
    @JsonProperty("crd_tp")
    private final String credit = "0";
    @JsonProperty("trde_qty_tp")
    private final String volume = "0";
    @JsonProperty("pric_tp")
    private final String price = "0";
    @JsonProperty("trde_prica_tp")
    private final String tradingValue = "0";
    @JsonProperty("mrkt_open_tp")
    private final String session = "0";
    @JsonProperty("stex_tp")
    private final String exchange = "1";

    public KiwoomStockRankingRequest(boolean volumeOrder) {
        sort = volumeOrder ? "1" : "3";
        includedClasses = volumeOrder ? "16" : "0";
    }
}
