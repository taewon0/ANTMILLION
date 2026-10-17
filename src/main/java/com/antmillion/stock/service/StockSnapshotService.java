package com.antmillion.stock.service;

import com.antmillion.stock.component.StockCache;
import com.antmillion.stock.dto.StockDTO;
import com.antmillion.stock.dto.SyncResult;
import com.antmillion.stock.mapper.StockSyncMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class StockSnapshotService {
    private final StockSyncMapper stockSyncMapper;
    private final StockCache stockCache;
    private final TransactionTemplate transaction;
    private final double minimumRetentionRatio;

    public StockSnapshotService(StockSyncMapper stockSyncMapper, StockCache stockCache,
            PlatformTransactionManager transactionManager,
            @Value("${stock.sync.minimum-retention-ratio:0.95}") double minimumRetentionRatio) {
        if (!Double.isFinite(minimumRetentionRatio) || minimumRetentionRatio < 0 || minimumRetentionRatio > 1) {
            throw new IllegalArgumentException("종목 유지 비율은 0~1이어야 합니다.");
        }
        this.stockSyncMapper = stockSyncMapper;
        this.stockCache = stockCache;
        this.transaction = new TransactionTemplate(transactionManager);
        this.minimumRetentionRatio = minimumRetentionRatio;
    }

    // 다운로드/파싱은 트랜잭션 밖에서 끝내고, DB 변경은 annotation 설정과 무관하게 원자적으로 적용합니다.
    public synchronized SyncResult applySnapshot(List<StockDTO> kospi, List<StockDTO> kosdaq) {
        if (kospi == null || kospi.isEmpty() || kosdaq == null || kosdaq.isEmpty()) {
            throw new IllegalArgumentException("두 시장의 종목 목록이 모두 필요합니다.");
        }
        List<StockDTO> stocks = new ArrayList<>(kospi);
        stocks.addAll(kosdaq);
        Set<String> codes = new HashSet<>();
        for (StockDTO stock : stocks) {
            if (stock == null || stock.getStockCode() == null
                    || !stock.getStockCode().matches("[A-Za-z0-9]{6}")
                    || stock.getStockName() == null || stock.getStockName().trim().isEmpty()
                    || stock.getStockName().length() > 100 || !codes.add(stock.getStockCode())) {
                throw new IllegalArgumentException("중복되거나 잘못된 종목 목록입니다.");
            }
        }
        return transaction.execute(status -> {
            Set<String> existing = new HashSet<>(stockSyncMapper.selectAllCodesForUpdate());
            List<String> activeCodes = stockSyncMapper.selectActiveCodes();
            long retained = activeCodes.stream().filter(codes::contains).count();
            if (!activeCodes.isEmpty() && (double) retained / activeCodes.size() < minimumRetentionRatio) {
                throw new IllegalStateException("기존 활성 종목이 과도하게 누락되어 동기화를 중단했습니다.");
            }
            int inserted = (int) codes.stream().filter(code -> !existing.contains(code)).count();
            for (int i = 0; i < stocks.size(); i += 500) {
                stockSyncMapper.upsertStocks(stocks.subList(i, Math.min(i + 500, stocks.size())));
            }
            int deactivated = stockSyncMapper.deactivateMissing(new ArrayList<>(codes));
            int cancelled = stockSyncMapper.cancelInactiveOrders();
            // 캐시 데이터 준비 실패도 DB 변경을 롤백하며 공개는 커밋 후에 수행합니다.
            stockCache.refresh();
            return SyncResult.builder().beforeCount(activeCodes.size()).afterCount(stockSyncMapper.countStock())
                    .insertedCount(inserted).deletedCount(0).deactivatedCount(deactivated)
                    .cancelledOrderCount(cancelled).totalDownloaded(stocks.size()).build();
        });
    }
}
