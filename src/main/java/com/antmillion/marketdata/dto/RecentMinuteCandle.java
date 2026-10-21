package com.antmillion.marketdata.dto;

import lombok.Value;

/** 당일 최근 분봉의 HHmmss 시각과 원 단위 정수 가격입니다. */
@Value
public class RecentMinuteCandle {
    String time;
    long closePrice;
    long openPrice;
    long highPrice;
    long lowPrice;
}
