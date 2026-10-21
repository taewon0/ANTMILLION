package com.antmillion.marketdata.provider;

import com.antmillion.marketdata.dto.MinuteCandle;
import com.antmillion.marketdata.dto.MinuteChartRequest;
import com.antmillion.marketdata.dto.RecentMinuteCandle;

import java.util.List;

/** 국내 KRX 일별·당일 최근 분봉 조회 계약입니다. 실시간 WS는 포함하지 않습니다. */
public interface MinuteChartProvider {
    // 해당 일자 15:30 기준 조회입니다. 기존 반환 순서·가격 문자열·빈 목록·null 행과 예외를 유지합니다.
    List<MinuteCandle> getDailyMinutes(MinuteChartRequest request);

    // 조회 시각은 기존 서비스의 서버 시각이며, 숫자 가격·null 목록/행과 예외를 유지합니다.
    List<RecentMinuteCandle> getRecentMinutes(String stockCode);
}
