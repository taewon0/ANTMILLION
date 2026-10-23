package com.antmillion.kiwoom.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 순위 계약에 필요한 필드만 읽으며 원격 메시지와 toString을 노출하지 않습니다. */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class KiwoomStockRankingResponse {
    @JsonProperty("return_code")
    @JsonAlias("returnCode")
    private Integer returnCode;
    @JsonProperty("tdy_trde_qty_upper")
    private List<Stock> stocks;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stock {
        @JsonProperty("stk_nm")
        private String stockName;
        @JsonProperty("stk_cd")
        private String stockCode;
        @JsonProperty("cur_prc")
        private String currentPrice;
        @JsonProperty("trde_qty")
        private String accumulatedVolume;
        @JsonProperty("flu_rt")
        private String changeRate;
    }
}
