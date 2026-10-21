package com.antmillion.kis.service;

import com.antmillion.kis.dto.CurrentPriceRequest;
import com.antmillion.marketdata.dto.CurrentQuote;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class KisCurrentPriceProvider implements CurrentPriceProvider {
    private final KisApiService kisApiService;

    @Override
    public Integer getCurrentPrice(String stockCode) {
        return kisApiService.getCurrentPrice(CurrentPriceRequest.builder()
                .marketCode("J")
                .stockCode(stockCode)
                .build());
    }

    @Override
    public List<CurrentQuote> getCurrentPrices(List<String> stockCodes) {
        // 기존 토큰·캐시·호출 제한·부분 실패 정책을 재사용하고 KIS 캐시 객체는 노출하지 않습니다.
        return kisApiService.getCurrentPricesDetail(stockCodes).stream()
                .map(price -> new CurrentQuote(price.getStockCode(), price.getCurrentPrice(), price.getPrdyCtrt()))
                .collect(Collectors.toList());
    }
}
