package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/** 지수 일봉의 업종 코드와 기준일입니다. */
@Getter
public final class KiwoomMarketIndexChartRequest {
    @JsonProperty("inds_cd")
    private final String indexCode;
    @JsonProperty("base_dt")
    private final String baseDate;

    public KiwoomMarketIndexChartRequest(String indexCode, String baseDate) {
        this.indexCode = indexCode;
        this.baseDate = baseDate;
    }
}
