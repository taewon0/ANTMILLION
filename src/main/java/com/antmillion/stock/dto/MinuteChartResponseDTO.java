package com.antmillion.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

/** 기존 일별 분봉 HTTP 응답의 필드와 문자열 타입을 유지합니다. */
@Value
public class MinuteChartResponseDTO {
    @JsonProperty("stck_bsop_date")
    String date;
    @JsonProperty("stck_cntg_hour")
    String time;
    @JsonProperty("stck_prpr")
    String closePrice;
    @JsonProperty("stck_oprc")
    String openPrice;
    @JsonProperty("stck_hgpr")
    String highPrice;
    @JsonProperty("stck_lwpr")
    String lowPrice;
}
