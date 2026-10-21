package com.antmillion.marketdata.dto;

import lombok.Value;

import java.time.LocalDate;

/** 국내 KRX 수정주가의 기간별 조회 조건입니다. 기간 코드는 기존 D/W/M/Y 값을 사용합니다. */
@Value
public class PeriodChartRequest {
    String stockCode;
    String period;
    LocalDate startDate;
    LocalDate endDate;
}
