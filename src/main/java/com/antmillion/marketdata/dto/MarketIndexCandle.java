package com.antmillion.marketdata.dto;

import lombok.Value;

/** 일자는 yyyyMMdd, 지수는 포인트 단위 원본 문자열이며 소수·빈 값·누락 값을 보존합니다. */
@Value
public class MarketIndexCandle {
    String date;
    String closeValue;
    String openValue;
    String highValue;
    String lowValue;
}
