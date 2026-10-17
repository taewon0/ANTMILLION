package com.antmillion.stock.scheduler;

import com.antmillion.stock.component.StockCache;
import com.antmillion.stock.dto.StockDTO;
import com.antmillion.stock.mapper.StockSyncRunMapper;
import com.antmillion.stock.service.KisStockMasterSource;
import com.antmillion.stock.service.StockSnapshotService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration("classpath:stock-preservation-context.xml")
public class StockSyncJobTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneOffset.UTC);
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private StockCache cache;
    @Autowired private StockSnapshotService snapshots;
    @Autowired private StockSyncRunMapper runs;
    @Autowired private PlatformTransactionManager transactionManager;
    private StockSyncLock lock;
    private int downloads;

    @Before
    public void setUp() {
        lock = new StockSyncLock(dataSource);
        for (String code : List.of("DAILY1", "DAILY2", "DAILY3")) {
            jdbc.update("INSERT INTO stock(stock_code, stock_name) VALUES(?,?)", code, "합성" + code);
        }
        cache.refresh();
    }

    @After
    public void cleanup() {
        jdbc.update("DELETE FROM stock_sync_run WHERE run_date IN (?,?,?)", DATE.minusDays(1), DATE, DATE.plusDays(1));
        jdbc.update("DELETE FROM stock WHERE stock_code IN ('DAILY1','DAILY2','DAILY3','NEW001')");
        cache.refresh();
    }

    @Test
    public void succeedsOncePerDateAcrossInstancesAndRunsAgainNextDay() {
        assertEquals(StockSyncJob.Outcome.SUCCEEDED, job(source(false), runs, CLOCK).runDailySync());
        assertEquals("SUCCEEDED", status());
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT attempt_count FROM stock_sync_run WHERE run_date=?", Integer.class, DATE));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT deactivated_count FROM stock_sync_run WHERE run_date=?", Integer.class, DATE));
        assertNull(cache.getStockByCode("DAILY1"));
        assertEquals(2, downloads);
        assertEquals(StockSyncJob.Outcome.ALREADY_SUCCEEDED, job(source(false), runs, CLOCK).runDailySync());
        assertEquals(2, downloads);
        assertEquals(StockSyncJob.Outcome.SUCCEEDED,
                job(source(false), runs, Clock.offset(CLOCK, java.time.Duration.ofDays(1))).runDailySync());
        assertEquals(4, downloads);
    }

    @Test
    public void recordsDownloadFailureWithoutChangesAndAllowsLaterRetry() {
        assertEquals(StockSyncJob.Outcome.FAILED, job(source(true), runs, CLOCK).runDailySync());
        assertEquals("FAILED", status());
        assertEquals("IOException", jdbc.queryForObject("SELECT failure_type FROM stock_sync_run WHERE run_date=?", String.class, DATE));
        assertNotNull(cache.getStockByCode("DAILY1"));
        assertEquals(Integer.valueOf(3), jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE active=1", Integer.class));
        assertEquals(StockSyncJob.Outcome.SUCCEEDED, job(source(false), runs, CLOCK).runDailySync());
        assertEquals(Integer.valueOf(2), jdbc.queryForObject("SELECT attempt_count FROM stock_sync_run WHERE run_date=?", Integer.class, DATE));
        assertNull(jdbc.queryForObject("SELECT failure_type FROM stock_sync_run WHERE run_date=?", String.class, DATE));
        try (StockSyncLock.Handle handle = lock.tryAcquire()) {
            assertNotNull(handle);
            handle.assertHeld();
        } catch (java.sql.SQLException e) { throw new AssertionError(e); }
    }

    @Test
    public void completionRecordFailureRollsBackDataAndCacheTogether() {
        StockSyncRunMapper failing = (StockSyncRunMapper) Proxy.newProxyInstance(StockSyncRunMapper.class.getClassLoader(),
                new Class<?>[]{StockSyncRunMapper.class}, (proxy, method, args) -> {
                    if ("complete".equals(method.getName())) throw new IllegalStateException("synthetic completion failure");
                    try { return method.invoke(runs, args); }
                    catch (InvocationTargetException e) { throw e.getCause(); }
                });
        assertEquals(StockSyncJob.Outcome.FAILED, job(source(false), failing, CLOCK).runDailySync());
        assertEquals("FAILED", status());
        assertEquals(Integer.valueOf(3), jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE active=1", Integer.class));
        assertNotNull(cache.getStockByCode("DAILY1"));
    }

    @Test
    public void concurrentSessionSkipsWithoutDownloadOrRunClaim() throws Exception {
        try (StockSyncLock.Handle held = lock.tryAcquire()) {
            assertNotNull(held);
            assertEquals(StockSyncJob.Outcome.LOCKED, job(source(false), runs, CLOCK).runDailySync());
            assertEquals(0, downloads);
            assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM stock_sync_run", Integer.class));
        }
        assertEquals(StockSyncJob.Outcome.SUCCEEDED, job(source(false), runs, CLOCK).runDailySync());
    }

    @Test
    public void reclaimsOrphanedAttemptAndStaleFailureCannotOverwriteSuccess() {
        String oldId = UUID.randomUUID().toString();
        runs.insertIfAbsent(DATE.minusDays(1), oldId, LocalDateTime.of(2026, 10, 19, 18, 0));
        runs.insertIfAbsent(DATE, oldId, LocalDateTime.of(2026, 10, 20, 18, 0));
        runs.startAttempt(DATE, oldId, LocalDateTime.of(2026, 10, 20, 18, 0));
        assertEquals(StockSyncJob.Outcome.SUCCEEDED, job(source(false), runs, CLOCK).runDailySync());
        assertEquals(0, runs.fail(DATE, oldId, LocalDateTime.of(2026, 10, 20, 18, 1), "old-failure"));
        assertEquals("SUCCEEDED", status());
        assertEquals("InterruptedRun", jdbc.queryForObject(
                "SELECT failure_type FROM stock_sync_run WHERE run_date=?", String.class, DATE.minusDays(1)));
        assertEquals(Integer.valueOf(2), jdbc.queryForObject("SELECT attempt_count FROM stock_sync_run WHERE run_date=?", Integer.class, DATE));
    }

    private StockSyncJob job(KisStockMasterSource source, StockSyncRunMapper mapper, Clock clock) {
        return new StockSyncJob(new StockSyncProperties(true, "0 0 18 * * *", "Asia/Seoul"),
                source, snapshots, mapper, lock, transactionManager, clock);
    }

    private KisStockMasterSource source(boolean failSecondMarket) {
        return new KisStockMasterSource() {
            @Override public List<StockDTO> loadStocks(String market) throws IOException {
                downloads++;
                if (failSecondMarket && "KOSDAQ".equals(market)) throw new IOException("synthetic download failure");
                String code = "KOSPI".equals(market) ? "DAILY2" : "DAILY3";
                return Collections.singletonList(StockDTO.builder().stockCode(code).stockName("유지" + code).build());
            }
        };
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM stock_sync_run WHERE run_date=?", String.class, DATE);
    }
}
