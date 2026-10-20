package com.antmillion.stock.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class AssetDTO {
	private Long assetId;
    private Long accountId;        // 계좌 외래 키: account.account_id
    private String stockCode;      // 종목 외래 키: stock.stock_code
    private Integer quantity;
    private LocalDateTime updatedAt;
    private Long purchaseAmount;
    private double avgPrice;   // 소수 둘째 자리까지 저장하는 금액 형식: DECIMAL(15,2)
}
