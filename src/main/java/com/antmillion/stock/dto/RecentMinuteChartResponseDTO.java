package com.antmillion.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

/** 기존 당일 최근 분봉 HTTP 응답의 필드와 숫자 타입을 유지합니다. */
@Value
public class RecentMinuteChartResponseDTO {
    @JsonProperty("stck_cntg_hour")
    String time;
    @JsonProperty("stck_prpr")
    long closePrice;
    @JsonProperty("stck_oprc")
    long openPrice;
    @JsonProperty("stck_hgpr")
    long highPrice;
    @JsonProperty("stck_lwpr")
    long lowPrice;
}
