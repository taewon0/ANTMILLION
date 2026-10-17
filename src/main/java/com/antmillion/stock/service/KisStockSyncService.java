package com.antmillion.stock.service;

import com.antmillion.stock.dto.StockDTO;
import com.antmillion.stock.dto.SyncResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KisStockSyncService {
    private final KisStockMasterSource masterSource;
    private final StockSnapshotService snapshotService;

    public SyncResult syncAllStocks() throws Exception {
        long start = System.currentTimeMillis();
        List<StockDTO> kospi = masterSource.loadStocks("KOSPI");
        List<StockDTO> kosdaq = masterSource.loadStocks("KOSDAQ");
        SyncResult result = snapshotService.applySnapshot(kospi, kosdaq);
        return SyncResult.builder().beforeCount(result.getBeforeCount()).afterCount(result.getAfterCount())
                .insertedCount(result.getInsertedCount()).deletedCount(0)
                .deactivatedCount(result.getDeactivatedCount()).cancelledOrderCount(result.getCancelledOrderCount())
                .totalDownloaded(result.getTotalDownloaded()).executionTimeMs(System.currentTimeMillis() - start).build();
    }

    // 기존 Java 호출 계약은 유지하며 공유 임시 파일은 만들지 않습니다.
    public SyncResult syncAllStocks(String baseDir) throws Exception {
        return syncAllStocks();
    }

    public List<StockDTO> loadKospiStocks(String baseDir) throws Exception {
        return masterSource.loadStocks("KOSPI");
    }

    public List<StockDTO> loadKosdaqStocks(String baseDir) throws Exception {
        return masterSource.loadStocks("KOSDAQ");
    }
}
