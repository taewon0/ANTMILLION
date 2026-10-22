package com.antmillion.marketdata.provider;

import com.antmillion.marketdata.dto.RankedStock;

import java.util.List;

/** 기존 국내 KRX 종목 순위의 두 조회 계약입니다. 원본 순서·누락 값·빈 결과·예외를 보존합니다. */
public interface StockRankingProvider {
    // 메인·종목 목록의 기존 거래대금순 조회 조건을 사용합니다.
    List<RankedStock> getTradingValueRanks();

    // 코드 목록 필터링에 쓰는 기존 거래량순 조회 조건과 제외 대상을 사용합니다.
    List<RankedStock> getVolumeRanks();
}
