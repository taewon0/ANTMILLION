package com.antmillion.marketdata.provider;

import com.antmillion.marketdata.dto.MarketIndexCandle;
import com.antmillion.marketdata.dto.MarketIndexChartRequest;

import java.util.List;

/** 국내 시장 지수의 일봉 조회 계약입니다. 종목 가격 차트와 구분합니다. */
public interface MarketIndexChartProvider {
    // 원본 순서·빈 목록·null 행·지수 문자열 및 조회 예외를 그대로 전달합니다.
    List<MarketIndexCandle> getMarketIndexChart(MarketIndexChartRequest request);
}
