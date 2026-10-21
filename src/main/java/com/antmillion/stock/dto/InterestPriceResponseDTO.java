package com.antmillion.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

/** 관심종목 화면의 기존 JSON 필드는 Provider 계약과 별도로 유지합니다. */
@Value
public class InterestPriceResponseDTO {
    String stockCode;
    String stockName;
    @JsonProperty("stck_prpr")
    String currentPrice;
    @JsonProperty("prdy_ctrt")
    String changeRate;
}
