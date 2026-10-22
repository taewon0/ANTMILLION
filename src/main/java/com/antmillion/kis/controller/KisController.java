package com.antmillion.kis.controller;

import com.antmillion.kis.dto.*;
import com.antmillion.kis.manager.KisWebSocketManager;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.marketdata.dto.MarketIndexChartRequest;
import com.antmillion.marketdata.dto.PeriodChartRequest;
import com.antmillion.marketdata.dto.MinuteChartRequest;
import com.antmillion.marketdata.dto.RecentMinuteCandle;
import com.antmillion.marketdata.dto.RankedStock;
import com.antmillion.marketdata.provider.StockRankingProvider;
import com.antmillion.marketdata.provider.MinuteChartProvider;
import com.antmillion.marketdata.provider.MarketIndexChartProvider;
import com.antmillion.marketdata.provider.PeriodChartProvider;
import com.antmillion.stock.dto.MinuteChartResponseDTO;
import com.antmillion.stock.dto.MarketIndexChartResponseDTO;
import com.antmillion.stock.dto.PeriodChartResponseDTO;
import com.antmillion.stock.dto.RecentMinuteChartResponseDTO;
import com.antmillion.stock.dto.StockRankingResponseDTO;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/kis")
public class KisController {

    private final KisApiService kisApiService;
    private final KisWebSocketManager kisWebSocketManager;
    private final PeriodChartProvider periodChartProvider;
    private final MinuteChartProvider minuteChartProvider;
    private final MarketIndexChartProvider marketIndexChartProvider;
    private final StockRankingProvider stockRankingProvider;

    /**
     * 조회 시작일: 일봉 100일 전, 주봉 52주 전, 월봉 48개월 전, 년봉 30년 전.
     */
    @GetMapping("/periodChart/{stockCode}")
    public List<PeriodChartResponseDTO> periodChart(
            @PathVariable("stockCode") String stockCode,
            @RequestParam("period") String period
    ) {
        ZoneId KST = ZoneId.of("Asia/Seoul");
        LocalDateTime endDate = LocalDateTime.now(KST);

        LocalDateTime startDate;
        switch (period) {
            case "W": { startDate = endDate.minusWeeks(52); break; }
            case "M": { startDate = endDate.minusMonths(48); break; }
            case "Y": { startDate = endDate.minusYears(30); break; }
            default: { startDate = endDate.minusDays(100); }
        }

        PeriodChartRequest request = new PeriodChartRequest(stockCode, period,
                startDate.toLocalDate(), endDate.toLocalDate());
        return periodChartProvider.getPeriodChart(request).stream()
                .map(price -> price == null ? null : new PeriodChartResponseDTO(price.getDate(), price.getClosePrice(),
                        price.getOpenPrice(), price.getHighPrice(), price.getLowPrice()))
                .collect(Collectors.toList());
    }

    @GetMapping("/marketIndex/{indexCode}")
    public List<MarketIndexChartResponseDTO> marketIndexChart(
        @PathVariable("indexCode") String indexCode
    ) {
        ZoneId KST = ZoneId.of("Asia/Seoul");
        MarketIndexChartRequest request = new MarketIndexChartRequest(indexCode, LocalDate.now(KST));
        return marketIndexChartProvider.getMarketIndexChart(request).stream()
                .map(price -> price == null ? null : new MarketIndexChartResponseDTO(price.getDate(), price.getCloseValue(),
                        price.getOpenValue(), price.getHighValue(), price.getLowValue()))
                .collect(Collectors.toList());
    }
    
    // 국내 일별 분봉 조회
    @GetMapping("/stream/{stockCode}")
    public List<MinuteChartResponseDTO> getStreamMinute(@PathVariable("stockCode") String stockCode) {
        MinuteChartRequest request = new MinuteChartRequest(stockCode, LocalDate.now());
        return minuteChartProvider.getDailyMinutes(request).stream()
                .map(price -> price == null ? null : new MinuteChartResponseDTO(price.getDate(), price.getTime(),
                        price.getClosePrice(), price.getOpenPrice(), price.getHighPrice(), price.getLowPrice()))
                .collect(Collectors.toList());
    }
    
    @GetMapping("/candle/{stockCode}")
    public List<RecentMinuteChartResponseDTO> getRealtimeCandle(@PathVariable("stockCode") String stockCode) {
        List<RecentMinuteCandle> prices = minuteChartProvider.getRecentMinutes(stockCode);
        return prices == null ? null : prices.stream()
                .map(price -> price == null ? null : new RecentMinuteChartResponseDTO(price.getTime(),
                        price.getClosePrice(), price.getOpenPrice(), price.getHighPrice(), price.getLowPrice()))
                .collect(Collectors.toList());
    }
    // 종목 순위 조회(기존 거래대금순 조건)
    @GetMapping("/volumeRank")
    public List<StockRankingResponseDTO> kisStockVolumeRank() {
        return stockRankingProvider.getTradingValueRanks().stream()
                .map(StockRankingResponseDTO::from)
                .collect(Collectors.toList());
    }

    //종목 순위 - 페이징(기존 거래대금순 조건)
    @GetMapping("/volumeRank/paged")
    public Map<String, Object> kisStockVolumeRankPaged(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        List<RankedStock> allRanks = stockRankingProvider.getTradingValueRanks();

        //페이징 처리
        int start = (page - 1) * size;
        int end = Math.min(start + size, allRanks.size());

        List<StockRankingResponseDTO> pagedRanks = allRanks.subList(start, end).stream()
                .map(StockRankingResponseDTO::from)
                .collect(Collectors.toList());

        Map<String, Object> response =  new HashMap<>();
        response.put("data", pagedRanks);
        response.put("currentPage", page);
        response.put("totalItems", allRanks.size());
        response.put("totalPages", (int) Math.ceil((double) allRanks.size() / (double) size));

        return response;
    }

    //특정 종목 코드 목록으로 종목 정보 조회
    @PostMapping("/stocksByCode")
    public List<StockRankingResponseDTO> getStocksByCode(@RequestBody Map<String, List<String>> request) {
        List<String> stockCodes = request.get("stockCodes");

        if (stockCodes == null || stockCodes.isEmpty()) {
            return new ArrayList<>();
        }

        // 전체 거래량 순위에서 해당 종목들만 필터링
        List<RankedStock> allRanks = stockRankingProvider.getVolumeRanks();

        // stockCodes에 해당하는 종목만 필터링
        return allRanks.stream()
                .filter(stock -> stockCodes.contains(stock.getStockCode()))
                .map(StockRankingResponseDTO::from)
                .collect(Collectors.toList());
    }
    
    @GetMapping("/foreigner-organization/{stockCode}")
    public FrgnOrgnTrafficSignal getForeignerOrganization(@PathVariable("stockCode") String stockCode) {
    	return kisApiService.getTrafficSignal(stockCode);
    }

}
