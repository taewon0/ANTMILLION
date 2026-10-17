package com.antmillion.mypage.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockHoldingsDTO {
    private String name;           // 종목명 (DB)
    private String code;           // 종목코드 (DB)
    private long shares;           // 보유수량 (DB)
    private long buyPrice;         // 총 매수금액 (DB)
    private long avgBuyPrice;      // 평균 단가 (DB)
    
    private boolean active;
    private Long currentPrice;     // 평가 불가 시 null
    private Long totalValue;
    private Long profit;
    private Double profitRate;
}
