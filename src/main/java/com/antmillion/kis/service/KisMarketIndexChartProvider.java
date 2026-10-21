package com.antmillion.kis.service;

import com.antmillion.kis.dto.MarketIndexPriceRequest;
import com.antmillion.marketdata.dto.MarketIndexCandle;
import com.antmillion.marketdata.dto.MarketIndexChartRequest;
import com.antmillion.marketdata.provider.MarketIndexChartProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class KisMarketIndexChartProvider implements MarketIndexChartProvider {
    private final KisApiService kisApiService;

    @Override
    public List<MarketIndexCandle> getMarketIndexChart(MarketIndexChartRequest request) {
        // 기존 토큰·캐시·공유 호출 제한을 재사용하고 KIS 응답/캐시 객체는 노출하지 않습니다.
        return kisApiService.getMarketIndexPrices(MarketIndexPriceRequest.builder()
                .periodCode("D")
                .marketCode("U")
                .indexCode(request.getIndexCode())
                .endDate(request.getEndDate().format(DateTimeFormatter.BASIC_ISO_DATE))
                .build()).stream()
                .map(price -> price == null ? null : new MarketIndexCandle(price.getStockDate(), price.getPresentPrice(),
                        price.getOpenPrice(), price.getHighPrice(), price.getLowPrice()))
                .collect(Collectors.toList());
    }
}
