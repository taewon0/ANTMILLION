package com.antmillion.kis.controller;

import com.antmillion.kis.service.MockDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@Profile("dev-mock")
@RequestMapping("/api/kis/mock")
@RequiredArgsConstructor
public class MockDataController {
    private final MockDataService mockDataService;

    /**
     * Mock 종목 일괄 추가
     * POST /api/kis/mock/add-stocks
     * Body: [{"stockCode": "005930", "basePrice": 75000}, ...]
     */
    @PostMapping("/add-stocks")
    public Map<String, Object> addMockStocks(@RequestBody List<Map<String, Object>> stocks) {
        Map<String, Object> response = new HashMap<>();
        try {
            for (Map<String, Object> stock : stocks) {
                String stockCode = (String) stock.get("stockCode");
                int basePrice = (int) stock.get("basePrice");
                mockDataService.addMockStock(stockCode, basePrice);
            }
            response.put("success", true);
            response.put("message", "Mock 종목 추가 완료");
            response.put("count", mockDataService.getMockStockCount());
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Mock 종목 추가 실패: " + e.getMessage());
        }
        return response;
    }

    /**
     * Mock 데이터 전송 시작
     * POST /api/kis/mock/start
     */
    @PostMapping("/start")
    public Map<String, Object> startMockData() {
        Map<String, Object> response = new HashMap<>();
        try {
            mockDataService.startMockData();
            response.put("success", true);
            response.put("message", "Mock 데이터 전송 시작");
            response.put("isRunning", mockDataService.isRunning());
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Mock 데이터 시작 실패: " + e.getMessage());
        }
        return response;
    }

    /**
     * Mock 데이터 전송 중지
     * POST /api/kis/mock/stop
     */
    @PostMapping("/stop")
    public Map<String, Object> stopMockData() {
        Map<String, Object> response = new HashMap<>();
        try {
            mockDataService.stopMockData();
            response.put("success", true);
            response.put("message", "Mock 데이터 전송 중지");
            response.put("isRunning", mockDataService.isRunning());
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Mock 데이터 중지 실패: " + e.getMessage());
        }
        return response;
    }

    /**
     * Mock 데이터 상태 확인
     * GET /api/kis/mock/status
     */
    @GetMapping("/status")
    public Map<String, Object> getMockStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("isRunning", mockDataService.isRunning());
        response.put("stockCount", mockDataService.getMockStockCount());
        return response;
    }

    /**
     * 모든 Mock 종목 제거
     * POST /api/kis/mock/clear
     */
    @PostMapping("/clear")
    public Map<String, Object> clearMockStocks() {
        Map<String, Object> response = new HashMap<>();
        try {
            mockDataService.clearAllMockStocks();
            response.put("success", true);
            response.put("message", "모든 Mock 종목 제거 완료");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Mock 종목 제거 실패: " + e.getMessage());
        }
        return response;
    }
}
