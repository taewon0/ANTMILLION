package com.antmillion.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

/** 기존 시장 지수 차트 HTTP 응답 필드와 문자열 타입을 유지합니다. */
@Value
public class MarketIndexChartResponseDTO {
    @JsonProperty("stck_bsop_date")
    String date;
    @JsonProperty("bstp_nmix_prpr")
    String closeValue;
    @JsonProperty("bstp_nmix_oprc")
    String openValue;
    @JsonProperty("bstp_nmix_hgpr")
    String highValue;
    @JsonProperty("bstp_nmix_lwpr")
    String lowValue;
}
