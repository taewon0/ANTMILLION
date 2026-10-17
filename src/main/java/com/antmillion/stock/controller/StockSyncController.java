package com.antmillion.stock.controller;

import com.antmillion.stock.dto.SyncResult;
import com.antmillion.stock.service.KisStockSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Controller
@RequestMapping("/stock")
@RequiredArgsConstructor
public class StockSyncController {

    private final KisStockSyncService kisStockSyncService;

    @GetMapping("/sync")
    public String syncPage() {
        return "stock/syncStock";
    }

    @PostMapping("/sync-execute")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> executeSync() {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("종목 동기화 시작 요청");

            SyncResult result = kisStockSyncService.syncAllStocks();

            response.put("success", true);
            response.put("message", "종목 동기화가 완료되었습니다.");
            response.put("beforeCount", result.getBeforeCount());
            response.put("afterCount", result.getAfterCount());
            response.put("insertedCount", result.getInsertedCount());
            response.put("deletedCount", result.getDeletedCount());
            response.put("deactivatedCount", result.getDeactivatedCount());
            response.put("cancelledOrderCount", result.getCancelledOrderCount());
            response.put("totalDownloaded", result.getTotalDownloaded());
            response.put("executionTimeMs", result.getExecutionTimeMs());
            response.put("executionTimeSec", result.getExecutionTimeMs() / 1000.0);

            log.info("종목 동기화 완료: {}", result);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("종목 동기화 실패", e);

            response.put("success", false);
            response.put("message", "동기화 중 오류가 발생했습니다: " + e.getMessage());

            return ResponseEntity.status(500).body(response);
        }
    }

}
