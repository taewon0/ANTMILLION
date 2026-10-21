package com.antmillion.marketdata.dto;

import lombok.Value;

/** 일자는 yyyyMMdd, 가격은 원 단위 원본 문자열이며 빈 값과 누락 값을 보존합니다. */
@Value
public class PeriodCandle {
    String date;
    String closePrice;
    String openPrice;
    String highPrice;
    String lowPrice;
}
