package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/** KRX 수정주가 차트의 기준일로 공통 요청의 종료일을 사용합니다. */
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
