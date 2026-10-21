package com.antmillion.marketdata.dto;

import lombok.Value;

/** 가격은 원, 전일 대비율은 퍼센트의 원본 문자열이며 누락 값은 보존합니다. */
@Value
public class CurrentQuote {
    String stockCode;
    String currentPrice;
    String changeRate;
}
