package com.antmillion.marketdata.provider;

import com.antmillion.marketdata.dto.CurrentQuote;

import java.util.List;

/** 현재 국내 KRX 종목의 현재가 조회만 제공하는 공통 계약입니다. */
public interface CurrentPriceProvider {
    // 기존 평가 경로의 null/0 이하 가격 및 조회 예외를 그대로 전달합니다.
    Integer getCurrentPrice(String stockCode);

    // 입력 순서를 유지하며 개별 조회 실패 종목은 목록에서 제외합니다.
    List<CurrentQuote> getCurrentPrices(List<String> stockCodes);
}
