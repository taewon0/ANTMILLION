package com.antmillion.marketdata.provider;

import com.antmillion.marketdata.dto.PeriodCandle;
import com.antmillion.marketdata.dto.PeriodChartRequest;

import java.util.List;

/**
 * 국내 KRX 수정주가의 일·주·월·년봉을 요청 기간 내 최신 최대 100건까지 제공합니다.
 * 원격 시세를 연속 조회해 기간을 채우지 않습니다. 분봉과 지수는 포함하지 않습니다.
 */
public interface PeriodChartProvider {
    // 원본 순서·빈 목록·null 행·가격 문자열 및 조회 예외를 그대로 전달합니다.
    List<PeriodCandle> getPeriodChart(PeriodChartRequest request);
}
