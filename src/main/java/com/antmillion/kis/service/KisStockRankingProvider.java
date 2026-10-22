package com.antmillion.kis.service;

import com.antmillion.kis.dto.StockVolumeRankRequest;
import com.antmillion.marketdata.dto.RankedStock;
import com.antmillion.marketdata.provider.StockRankingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class KisStockRankingProvider implements StockRankingProvider {
    private final KisApiService kisApiService;

    @Override
    public List<RankedStock> getTradingValueRanks() {
        return getRanks("3", "0000000000");
    }

    @Override
    public List<RankedStock> getVolumeRanks() {
        return getRanks("0", "0000001100");
    }

    private List<RankedStock> getRanks(String order, String excludedClasses) {
        // 기존 토큰·조건별 캐시·공유 제한을 재사용하고 KIS 응답/캐시 객체는 노출하지 않습니다.
        return kisApiService.getStockVolumeRanks(StockVolumeRankRequest.builder()
                .marketCode("J")
                .screenCode("20171")
                .inputCode("0000")
                .divClassCode("0")
                .blngClassCode(order)
                .targetClassCode("111111111")
                .targetExlsClassCode(excludedClasses)
                .inputPrice1("0")
                .inputPrice2("0")
                .volumeCount("0")
                .inputDate1("0")
                .build()).stream()
                .map(stock -> stock == null ? null : new RankedStock(stock.getStockName(), stock.getStockCode(),
                        stock.getRank(), stock.getPresentPrice(), stock.getAcmVolume(), stock.getPrdyCtrt(),
                        stock.getIsFavorite()))
                .collect(Collectors.toList());
    }
}
