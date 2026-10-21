package com.antmillion.kis.service;

import com.antmillion.kis.dto.ChartStockPriceRequest;
import com.antmillion.marketdata.dto.PeriodCandle;
import com.antmillion.marketdata.dto.PeriodChartRequest;
import com.antmillion.marketdata.provider.PeriodChartProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class KisPeriodChartProvider implements PeriodChartProvider {
    private final KisApiService kisApiService;

    @Override
    public List<PeriodCandle> getPeriodChart(PeriodChartRequest request) {
        // 기존 토큰·캐시·공유 호출 제한을 재사용하고 KIS 응답/캐시 객체는 노출하지 않습니다.
        return kisApiService.getPeriodStockPrices(ChartStockPriceRequest.builder()
                .marketCode("J")
                .stockCode(request.getStockCode())
                .periodCode(request.getPeriod())
                .startDate(request.getStartDate().format(DateTimeFormatter.BASIC_ISO_DATE))
                .endDate(request.getEndDate().format(DateTimeFormatter.BASIC_ISO_DATE))
                .adjPrice("0")
                .build()).stream()
                .map(price -> price == null ? null : new PeriodCandle(price.getStockDate(), price.getClosePrice(),
                        price.getOpenPrice(), price.getHighPrice(), price.getLowPrice()))
                .collect(Collectors.toList());
    }
}
