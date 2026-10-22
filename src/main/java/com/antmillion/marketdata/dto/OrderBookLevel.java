package com.antmillion.marketdata.dto;

import lombok.Value;

/** 가격은 원, 잔량은 주 단위의 원본 문자열입니다. 숫자 해석은 공통 전달 경계에서 수행합니다. */
@Value
public class OrderBookLevel {
    String price;
    String quantity;
}
