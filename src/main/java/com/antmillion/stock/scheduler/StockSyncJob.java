package com.antmillion.stock.scheduler;

import com.antmillion.stock.dto.StockDTO;
import com.antmillion.stock.dto.SyncResult;
import com.antmillion.stock.mapper.StockSyncRunMapper;
import com.antmillion.stock.service.KisStockMasterSource;
import com.antmillion.stock.service.StockSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class StockSyncJob {
    public enum Outcome { DISABLED, LOCKED, ALREADY_SUCCEEDED, SUCCEEDED, FAILED }

    private final StockSyncProperties properties;
    private final KisStockMasterSource source;
    private final StockSnapshotService snapshots;
    private final StockSyncRunMapper runs;
    private final StockSyncLock lock;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public StockSyncJob(StockSyncProperties properties, KisStockMasterSource source,
            StockSnapshotService snapshots, StockSyncRunMapper runs, StockSyncLock lock,
            PlatformTransactionManager transactionManager) {
        this(properties, source, snapshots, runs, lock, transactionManager, Clock.systemUTC());
    }

    StockSyncJob(StockSyncProperties properties, KisStockMasterSource source,
            StockSnapshotService snapshots, StockSyncRunMapper runs, StockSyncLock lock,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.properties = properties;
        this.source = source;
        this.snapshots = snapshots;
        this.runs = runs;
        this.lock = lock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock.withZone(properties.getZone());
    }

    public Outcome runDailySync() {
        if (!properties.isEnabled()) return Outcome.DISABLED;
        LocalDate date = LocalDate.now(clock);
        String runId = UUID.randomUUID().toString();
        Outcome outcome = Outcome.FAILED;
        try (StockSyncLock.Handle handle = lock.tryAcquire()) {
            if (handle == null) {
                log.info("STOCK_SYNC_SKIP date={} reason=LOCKED", date);
                return Outcome.LOCKED;
            }
            outcome = runLocked(handle, date, runId);
        } catch (Exception e) {
            // 정리 오류가 발생해도 이미 커밋된 성공 기록을 실패로 바꾸지 않습니다.
            log.error("STOCK_SYNC_RESOURCE_FAILURE date={} runId={} outcome={} type={}",
                    date, runId, outcome, e.getClass().getSimpleName());
        }
        return outcome;
    }

    private Outcome runLocked(StockSyncLock.Handle handle, LocalDate date, String runId) {
        runs.failAbandonedBefore(date, LocalDateTime.now(clock));
        runs.insertIfAbsent(date, runId, LocalDateTime.now(clock));
        if (runs.startAttempt(date, runId, LocalDateTime.now(clock)) == 0) {
            log.info("STOCK_SYNC_SKIP date={} reason=ALREADY_SUCCEEDED", date);
            return Outcome.ALREADY_SUCCEEDED;
        }
        String stage = "DOWNLOAD_KOSPI";
        try {
            log.info("STOCK_SYNC_START date={} runId={}", date, runId);
            List<StockDTO> kospi = source.loadStocks("KOSPI");
            stage = "DOWNLOAD_KOSDAQ";
            List<StockDTO> kosdaq = source.loadStocks("KOSDAQ");
            stage = "APPLY";
            SyncResult result = transaction.execute(status -> {
                if (!runId.equals(runs.lockRun(date))) throw new IllegalStateException("Stock sync ownership lost");
                handle.assertHeld();
                SyncResult applied = snapshots.applySnapshot(kospi, kosdaq);
                if (runs.complete(date, runId, LocalDateTime.now(clock), applied) != 1) {
                    throw new IllegalStateException("Stock sync completion record failed");
                }
                return applied;
            });
            log.info("STOCK_SYNC_SUCCESS date={} runId={} downloaded={} inserted={} deactivated={} cancelled={}",
                    date, runId, result.getTotalDownloaded(), result.getInsertedCount(),
                    result.getDeactivatedCount(), result.getCancelledOrderCount());
            return Outcome.SUCCEEDED;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            try {
                runs.fail(date, runId, LocalDateTime.now(clock), e.getClass().getSimpleName());
            } catch (RuntimeException recordFailure) {
                log.error("STOCK_SYNC_RECORD_FAILURE date={} runId={} type={}",
                        date, runId, recordFailure.getClass().getSimpleName());
            }
            log.error("STOCK_SYNC_FAILURE date={} runId={} stage={} type={}",
                    date, runId, stage, e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }
}
