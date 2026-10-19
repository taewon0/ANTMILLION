package com.antmillion.kis.service;

import lombok.Getter;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;

@Service
@Profile("dev-mock")
public class MockDataService {
    private final SimpMessagingTemplate messagingTemplate;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask; //추가: 스케줄된 작업 참조
    private final Map<String, MockStockData> mockStocks = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private volatile boolean isRunning = false;

    public MockDataService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    // Mock 종목 추가
    public void addMockStock(String stockCode, int basePrice) {
        if (!mockStocks.containsKey(stockCode)) {
            mockStocks.put(stockCode, new MockStockData(stockCode, basePrice));
            System.out.println("Mock 종목 추가: " + stockCode + " (기준가: " + basePrice + ")");
        }
    }

    // Mock 종목 제거
    public void removeMockStock(String stockCode) {
        mockStocks.remove(stockCode);
        System.out.println("Mock 종목 제거: " + stockCode);
    }

    // 모든 Mock 종목 제거
    public void clearAllMockStocks() {
        mockStocks.clear();
        System.out.println("모든 Mock 종목 제거");
    }

    // Mock 데이터 전송 시작
    public synchronized void startMockData() {
        if (isRunning) {
            System.out.println("Mock 데이터가 이미 실행 중입니다.");
            return;
        }

        if (mockStocks.isEmpty()) {
            System.out.println("Mock 종목이 없습니다. 먼저 종목을 추가하세요.");
            return;
        }

        // 스케줄러 새로 생성 (기존 스케줄러가 없거나 종료된 경우)
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newScheduledThreadPool(1);
            System.out.println("새 스케줄러 생성");
        }

        isRunning = true;
        System.out.println("Mock 데이터 전송 시작 (종목 수: " + mockStocks.size() + ")");

        // 1초마다 데이터 전송 (작업 참조 저장)
        scheduledTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                mockStocks.forEach((stockCode, mockData) -> {
                    sendMockTradeData(stockCode, mockData);
                });
            } catch (Exception e) {
                System.err.println("Mock 데이터 전송 오류: " + e.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    // Mock 데이터 전송 중지
    @PreDestroy
    public synchronized void stopMockData() {
        if (!isRunning) {
            System.out.println("Mock 데이터가 실행 중이지 않습니다.");
            return;
        }

        isRunning = false;

        // 스케줄된 작업 취소
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(false);
            System.out.println("Mock 스케줄 작업 취소");
        }

        // 스케줄러 종료
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                // 1초 대기 후 강제 종료
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
                System.out.println("Mock 스케줄러 종료");
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("Mock 데이터 전송 중지 완료");
    }

    // 체결가 Mock 데이터 전송
    private void sendMockTradeData(String stockCode, MockStockData mockData) {
        int currentPrice = mockData.getCurrentPrice();
        int priceChange;

        // 저가 주식 대응
        if (currentPrice < 100) {
            priceChange = random.nextInt(7) - 3; // -3 ~ +3원
            if (priceChange == 0) {
                priceChange = random.nextBoolean() ? 1 : -1;
            }
        }
        else if (currentPrice < 1000) {
            priceChange = random.nextInt(11) - 5; // -5 ~ +5원
            if (priceChange == 0) {
                priceChange = random.nextBoolean() ? 1 : -1;
            }
        }
        else {
            priceChange = (int) (currentPrice * (random.nextDouble() * 0.04 - 0.02));
            if (Math.abs(priceChange) < 10) {
                priceChange = random.nextBoolean() ? 10 : -10;
            }
        }

        int newPrice = currentPrice + priceChange;

        // 최소 가격 보정
        if (newPrice < 1) {
            newPrice = 1;
        }

        // 이전 가격과 비교
        int prevPrice = mockData.getPreviousPrice();
        int prdyVrss = newPrice - prevPrice;
        double prdyCtrt = prevPrice != 0 ? ((double) prdyVrss / prevPrice) * 100 : 0;
        String prdySign = prdyVrss > 0 ? "1" : (prdyVrss < 0 ? "4" : "3");

//        // 매수 비율 (40~60%)
//        int shnuRate = 40 + random.nextInt(21);

        double shnuRate = Math.round((0.4 + random.nextDouble() * 0.2) * 100.0) / 100.0;

        Map<String, String> tradeData = new HashMap<>();
        tradeData.put("mkscShrnIscd", stockCode);
        tradeData.put("stckPrpr", String.valueOf(newPrice));
        tradeData.put("prdySign", prdySign);
        tradeData.put("prdyVrss", String.valueOf(Math.abs(prdyVrss)));
        tradeData.put("prdyCtrt", String.format("%.2f", Math.abs(prdyCtrt)));
        tradeData.put("shnuRate", String.valueOf(shnuRate));

        // 현재 가격 업데이트
        mockData.updatePrice(newPrice);

        // STOMP로 전송
        messagingTemplate.convertAndSend("/topic/kis-trade/present" + stockCode, tradeData);

        System.out.println("Mock 체결가 전송: " + stockCode + " - " + newPrice + "원");
    }

    public boolean isRunning() {
        return isRunning;
    }

    public int getMockStockCount() {
        return mockStocks.size();
    }

    // 내부 클래스: 종목별 Mock 데이터
    private static class MockStockData {
        private final String stockCode;
        private final int basePrice;
        @Getter
        private int currentPrice;
        @Getter
        private int previousPrice;

        public MockStockData(String stockCode, int basePrice) {
            this.stockCode = stockCode;
            this.basePrice = basePrice;
            this.currentPrice = basePrice;
            this.previousPrice = basePrice;
        }

        public void updatePrice(int newPrice) {
            this.previousPrice = this.currentPrice;
            this.currentPrice = newPrice;
        }
    }
}
