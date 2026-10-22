package com.antmillion.marketdata.dto;

import lombok.Value;

/** 국내 종목 순위입니다. 원 단위 가격·주 단위 거래량·퍼센트 대비율의 원본 문자열을 보존합니다. */
@Value
public class RankedStock {
    String stockName;
    String stockCode;
    String rank;
    String currentPrice;
    String accumulatedVolume;
    String changeRate;
    // 기존 응답의 관심종목 표시값을 복사하며 Provider에서 사용자별 관심 여부를 조회하지 않습니다.
    Boolean favorite;
}
