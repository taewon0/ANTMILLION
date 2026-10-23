package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/** KRX 1분봉·수정주가 1을 사용하며 조회 날짜를 명시합니다. */
@Getter
public final class KiwoomMinuteChartRequest {
    @JsonProperty("stk_cd")
    private final String stockCode;
    @JsonProperty("tic_scope")
    private final String tickScope = "1";
    @JsonProperty("upd_stkpc_tp")
    private final String adjustedPrice = "1";
    @JsonProperty("base_dt")
    private final String baseDate;

    public KiwoomMinuteChartRequest(String stockCode, String baseDate) {
        this.stockCode = stockCode;
        this.baseDate = baseDate;
    }
}
