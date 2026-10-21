package com.antmillion.kis.controller;

import com.antmillion.kis.dto.*;
import com.antmillion.kis.manager.KisWebSocketManager;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.marketdata.dto.PeriodChartRequest;
import com.antmillion.marketdata.provider.PeriodChartProvider;
import com.antmillion.stock.dto.PeriodChartResponseDTO;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    public List<MarketIndexPrice> marketIndexChart(
        @PathVariable("indexCode") String indexCode
    ) {
        ZoneId KST = ZoneId.of("Asia/Seoul");
        LocalDateTime endDate = LocalDateTime.now(KST);
        String endDateStr = endDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        MarketIndexPriceRequest request = MarketIndexPriceRequest.builder()
                .periodCode("D")
                .marketCode("U")
                .indexCode(indexCode)
                .endDate(endDateStr)
                .build();
        return kisApiService.getMarketIndexPrices(request);
    }
    
    // 국내 일별 분봉 조회
    @GetMapping("/stream/{stockCode}")
    public List<StreamMinutePrice> getStreamMinute(@PathVariable String stockCode) {
        LocalDate localDate = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String today = localDate.format(formatter);
    	
    	StreamMinutePriceRequest request = StreamMinutePriceRequest.builder()
                .condMrktDivCode("J")
                .inputIscd(stockCode)
                .inputHour1("153000")
                .inputDate1(today)
                .pwDataIncuYn("N")
                .fakeTickIncuYn("")
                .build();

        return kisApiService.getStreamMinutePrices(request);
    }
    
    @GetMapping("/candle/{stockCode}")
    public List<DayMinutePrice> getRealtimeCandle(@PathVariable String stockCode) {
        DayMinutePriceRequest request = DayMinutePriceRequest.builder()
                .condMrktDivCode("J")
                .inputIscd(stockCode)
                .pwDataIncuYn("Y") // 과거 데이터 30개 포함
                .etcClsCode("0")
                .build();
        return kisApiService.getDayMinutePrices(request);
    }
    // 거래량순 조회
    @GetMapping("/volumeRank")
    public List<StockVolumeRank> kisStockVolumeRank() {
        StockVolumeRankRequest request = StockVolumeRankRequest.builder()
                .marketCode("J") //고정 : KRX
                .screenCode("20171") //고정
                .inputCode("0000") //고정 : 종목코드 전체
                .divClassCode("0") //고정 : 전체 (보통주, 우선주)
                .blngClassCode("3") //고정 : 거래대금액순
                .targetClassCode("111111111") //고정
                .targetExlsClassCode("0000000000") //고정
                .inputPrice1("0") //고정 : 전체 가격 대상
                .inputPrice2("0") //고정 : 전체 가격 대상
                .volumeCount("0") //고정 : 전체 거래량 대상
                .inputDate1("0") //고정
                .build();
        return kisApiService.getStockVolumeRanks(request);
    }

    //거래량 순위 - 페이징
    @GetMapping("/volumeRank/paged")
    public Map<String, Object> kisStockVolumeRankPaged(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        StockVolumeRankRequest request = StockVolumeRankRequest.builder()
                .marketCode("J")
                .screenCode("20171")
                .inputCode("0000")
                .divClassCode("0")
                .blngClassCode("3")
                .targetClassCode("111111111")
                .targetExlsClassCode("0000000000")
                .inputPrice1("0")
                .inputPrice2("0")
                .volumeCount("0")
                .inputDate1("0")
                .build();
        List<StockVolumeRank> allRanks = kisApiService.getStockVolumeRanks(request);

        //페이징 처리
        int start = (page - 1) * size;
        int end = Math.min(start + size, allRanks.size());

        List<StockVolumeRank> pagedRanks = allRanks.subList(start, end);

        Map<String, Object> response =  new HashMap<>();
        response.put("data", pagedRanks);
        response.put("currentPage", page);
        response.put("totalItems", allRanks.size());
        response.put("totalPages", (int) Math.ceil((double) allRanks.size() / (double) size));

        return response;
    }

    //특정 종목 코드 목록으로 종목 정보 조회
    @PostMapping("/stocksByCode")
    public List<StockVolumeRank> getStocksByCode(@RequestBody Map<String, List<String>> request) {
        List<String> stockCodes = request.get("stockCodes");

        if (stockCodes == null || stockCodes.isEmpty()) {
            return new ArrayList<>();
        }

        // 전체 거래량 순위에서 해당 종목들만 필터링
        StockVolumeRankRequest rankRequest = StockVolumeRankRequest.builder()
                .marketCode("J")
                .screenCode("20171")
                .inputCode("0000")
                .divClassCode("0")
                .blngClassCode("0")
                .targetClassCode("111111111")
                .targetExlsClassCode("0000001100")
                .inputPrice1("0")
                .inputPrice2("0")
                .volumeCount("0")
                .inputDate1("0")
                .build();

        List<StockVolumeRank> allRanks = kisApiService.getStockVolumeRanks(rankRequest);

        // stockCodes에 해당하는 종목만 필터링
        return allRanks.stream()
                .filter(stock -> stockCodes.contains(stock.getStockCode()))
                .collect(Collectors.toList());
    }
    
    @GetMapping("/foreigner-organization/{stockCode}")
    public FrgnOrgnTrafficSignal getForeignerOrganization(@PathVariable("stockCode") String stockCode) {
    	return kisApiService.getTrafficSignal(stockCode);
    }

}
