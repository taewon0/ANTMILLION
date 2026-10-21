package com.antmillion.marketdata.dto;

import lombok.Value;

import java.time.LocalDate;

/** 국내 시장 지수의 일봉 조회 조건입니다. 지수 코드는 기존 0001/1001 등을 유지합니다. */
@Value
public class MarketIndexChartRequest {
    String indexCode;
    LocalDate endDate;
}
