package com.antmillion.stock.dto;

import com.antmillion.marketdata.dto.RankedStock;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

/** 기존 순위 조회 HTTP 응답 필드·문자열 타입·관심종목 표시값을 유지합니다. */
@Value
public class StockRankingResponseDTO {
    @JsonProperty("hts_kor_isnm")
    String stockName;
    @JsonProperty("mksc_shrn_iscd")
    String stockCode;
    @JsonProperty("data_rank")
    String rank;
    @JsonProperty("stck_prpr")
    String currentPrice;
    @JsonProperty("acml_vol")
    String accumulatedVolume;
    @JsonProperty("prdy_ctrt")
    String changeRate;
    Boolean isFavorite;

    public static StockRankingResponseDTO from(RankedStock stock) {
        return stock == null ? null : new StockRankingResponseDTO(stock.getStockName(), stock.getStockCode(),
                stock.getRank(), stock.getCurrentPrice(), stock.getAccumulatedVolume(), stock.getChangeRate(),
                stock.getFavorite());
    }
}
