package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/** KRX 수정주가 차트의 기준일을 연속 조회 내내 고정합니다. */
@Getter
public final class KiwoomPeriodChartRequest {
    @JsonProperty("stk_cd")
    private final String stockCode;
    @JsonProperty("base_dt")
    private final String baseDate;
    @JsonProperty("upd_stkpc_tp")
    private final String adjustedPrice = "1";

    public KiwoomPeriodChartRequest(String stockCode, String baseDate) {
        this.stockCode = stockCode;
        this.baseDate = baseDate;
    }
}
