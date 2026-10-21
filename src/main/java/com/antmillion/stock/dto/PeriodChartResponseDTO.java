package com.antmillion.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

/** 기존 기간별 차트 HTTP 응답 필드를 유지합니다. */
@Value
public class PeriodChartResponseDTO {
    @JsonProperty("stck_bsop_date")
    String date;
    @JsonProperty("stck_clpr")
    String closePrice;
    @JsonProperty("stck_oprc")
    String openPrice;
    @JsonProperty("stck_hgpr")
    String highPrice;
    @JsonProperty("stck_lwpr")
    String lowPrice;
}
