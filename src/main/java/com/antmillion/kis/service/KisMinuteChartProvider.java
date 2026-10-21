package com.antmillion.kis.service;

import com.antmillion.kis.dto.DayMinutePrice;
import com.antmillion.kis.dto.DayMinutePriceRequest;
import com.antmillion.kis.dto.StreamMinutePriceRequest;
import com.antmillion.marketdata.dto.MinuteCandle;
import com.antmillion.marketdata.dto.MinuteChartRequest;
import com.antmillion.marketdata.dto.RecentMinuteCandle;
import com.antmillion.marketdata.provider.MinuteChartProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class KisMinuteChartProvider implements MinuteChartProvider {
    private final KisApiService kisApiService;

    @Override
    public List<MinuteCandle> getDailyMinutes(MinuteChartRequest request) {
        // 기존 날짜별 캐시·정렬·공유 호출 제한을 재사용하고 KIS 캐시 객체를 노출하지 않습니다.
        return kisApiService.getStreamMinutePrices(StreamMinutePriceRequest.builder()
                .condMrktDivCode("J")
                .inputIscd(request.getStockCode())
                .inputHour1("153000")
                .inputDate1(request.getDate().format(DateTimeFormatter.BASIC_ISO_DATE))
                .pwDataIncuYn("N")
                .fakeTickIncuYn("")
                .build()).stream()
                .map(price -> price == null ? null : new MinuteCandle(price.getStckBsopDate(),
                        price.getStckCntgHour(), price.getStckPrpr(), price.getStckOprc(),
                        price.getStckHgpr(), price.getStckLwpr()))
                .collect(Collectors.toList());
    }

    @Override
    public List<RecentMinuteCandle> getRecentMinutes(String stockCode) {
        // 당일 최근 분봉은 기존 서비스의 현재 시각·직접 HTTP 경로와 숫자 가격을 유지합니다.
        List<DayMinutePrice> prices = kisApiService.getDayMinutePrices(DayMinutePriceRequest.builder()
                .condMrktDivCode("J")
                .inputIscd(stockCode)
                .pwDataIncuYn("Y")
                .etcClsCode("0")
                .build());
        return prices == null ? null : prices.stream()
                .map(price -> price == null ? null : new RecentMinuteCandle(price.getStckCntgHour(),
                        price.getStckPrpr(), price.getStckOprc(), price.getStckHgpr(), price.getStckLwpr()))
                .collect(Collectors.toList());
    }
}
