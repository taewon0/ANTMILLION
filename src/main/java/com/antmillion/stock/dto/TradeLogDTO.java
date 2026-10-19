package com.antmillion.stock.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class TradeLogDTO {
	@Setter
	private Long tradeId;
    private Long orderId;
    private LocalDateTime tradeDate;
    private Long tradePrice;
    private Integer tradeQuantity;
}
