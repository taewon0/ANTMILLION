package com.antmillion.marketdata.dto;

import lombok.Value;

import java.time.LocalDate;

/** 국내 KRX 종목의 일별 분봉 조회 조건입니다. */
@Value
public class MinuteChartRequest {
    String stockCode;
    LocalDate date;
}
