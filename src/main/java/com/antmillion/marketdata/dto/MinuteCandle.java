package com.antmillion.marketdata.dto;

import lombok.Value;

/** 일별 분봉의 yyyyMMdd 일자·HHmmss 시각과 원 단위 가격 문자열입니다. */
@Value
public class MinuteCandle {
    String date;
    String time;
    String closePrice;
    String openPrice;
    String highPrice;
    String lowPrice;
}
