package com.antmillion.marketdata.dto;

import lombok.Value;

/** 원 단위 현재가·대비 금액과 퍼센트 대비율·매수 비율의 원본 문자열을 보존합니다. */
@Value
public class RealtimePrice {
    String stockCode;
    String currentPrice;
    String changeSign;
    String changeAmount;
    String changeRate;
    String buyRatio;
}
