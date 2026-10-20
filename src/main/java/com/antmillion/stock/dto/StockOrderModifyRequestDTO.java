package com.antmillion.stock.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class StockOrderModifyRequestDTO {
    // Integer/Long 바인딩의 소수 절삭을 막기 위해 정확한 정수 변환 전까지 JSON 숫자를 보존합니다.
    private BigDecimal orderId;
    private BigDecimal quantity;
    private BigDecimal orderPrice;

    public StockOrderDTO toOrder() {
        StockOrderDTO order = new StockOrderDTO();
        try {
            order.setOrderId(orderId == null ? null : orderId.longValueExact());
            order.setQuantity(quantity == null ? null : quantity.intValueExact());
            order.setOrderPrice(orderPrice == null ? null : orderPrice.intValueExact());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("주문 ID·수량·가격은 허용 범위 내의 정수여야 합니다.");
        }
        return order;
    }
}
