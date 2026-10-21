package com.antmillion.stock.service;

import com.antmillion.marketdata.dto.CurrentQuote;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
import com.antmillion.mypage.dto.StockHoldingsDTO;
import com.antmillion.mypage.mapper.MyPageMapper;
import com.antmillion.mypage.service.MyPageServiceImpl;
import com.antmillion.stock.component.StockCache;
import com.antmillion.stock.dto.StockDTO;
import com.antmillion.stock.dto.StockOrderDTO;
import com.antmillion.stock.dto.SyncResult;
import com.antmillion.stock.mapper.StockMapper;
import com.antmillion.stock.mapper.StockOrderMapper;
import com.antmillion.stock.mapper.StockSyncMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration("classpath:stock-preservation-context.xml")
public class StockPreservationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StockSnapshotService snapshotService;
    @Autowired private StockSyncMapper syncMapper;
    @Autowired private StockMapper stockMapper;
    @Autowired private StockOrderMapper orderMapper;
    @Autowired private StockOrderService orderService;
    @Autowired private TradingEngineService engine;
    @Autowired private StockCache cache;
    @Autowired private MyPageMapper myPageMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    private Long userId;
    private Long accountId;
    private Long partialOrderId;

    @Before
    public void fixtures() {
        jdbc.update("INSERT INTO member(rank_id,nickname,provider) VALUES(NULL,?,'LOCAL')", "preserve-" + UUID.randomUUID());
        userId = jdbc.queryForObject("SELECT user_id FROM member WHERE nickname LIKE 'preserve-%'", Long.class);
        jdbc.update("INSERT INTO account(user_id,account_number,balance) VALUES(?,?,1000000)", userId, "fixture-" + UUID.randomUUID());
        accountId = jdbc.queryForObject("SELECT account_id FROM account WHERE user_id=?", Long.class, userId);
        for (String code : Arrays.asList("TEST01", "TEST02", "TEST03")) {
            jdbc.update("INSERT INTO stock(stock_code,stock_name) VALUES(?,?)", code, "합성" + code);
        }
        jdbc.update("INSERT INTO asset(account_id,stock_code,quantity,purchase_amount,avg_price) VALUES(?,'TEST01',10,10000,1000)", accountId);
        partialOrderId = order("TEST01", "PARTIAL");
        Long completed = order("TEST01", "COMPLETED");
        order("TEST01", "WAIT");
        for (Long id : Arrays.asList(partialOrderId, completed)) {
            jdbc.update("INSERT INTO trade_log(order_id,trade_date,trade_price,trade_quantity) VALUES(?,NOW(),1100,2)", id);
        }
        Long tradeId = jdbc.queryForObject("SELECT MIN(trade_id) FROM trade_log WHERE order_id=?", Long.class, partialOrderId);
        jdbc.update("INSERT INTO realized_pnl(stock_code,trade_id,account_id,sell_date,sell_quantity,sell_amount,cost_basis,realized_profit,avg_price) VALUES('TEST01',?,?,NOW(),1,1100,1000,100,1000)", tradeId, accountId);
        cache.refresh();
    }

    // 테스트 트랜잭션으로 애플리케이션의 커밋·롤백을 가리지 않으며 정리는 안전 검사가 적용된 일회성 DB에서만 수행합니다.
    @After
    public void cleanup() {
        if (accountId != null) {
            jdbc.update("DELETE FROM realized_pnl WHERE account_id=?", accountId);
        }
        if (userId != null) jdbc.update("DELETE FROM member WHERE user_id=?", userId);
        jdbc.update("DELETE FROM stock WHERE stock_code IN ('TEST01','TEST02','TEST03','NEW001')");
        cache.refresh();
    }

    @Test
    public void deactivationPreservesAssetsTradesPnlAndReadablePartialExecutions() {
        List<Map<String, Object>> assets = jdbc.queryForList("SELECT * FROM asset WHERE account_id=?", accountId);
        List<Map<String, Object>> trades = jdbc.queryForList("SELECT * FROM trade_log ORDER BY trade_id");
        List<Map<String, Object>> pnl = jdbc.queryForList("SELECT * FROM realized_pnl WHERE account_id=?", accountId);
        SyncResult result = deactivate();
        assertEquals(1, result.getDeactivatedCount());
        assertEquals(2, result.getCancelledOrderCount());
        assertEquals(0, result.getDeletedCount());
        assertEquals(Integer.valueOf(3), jdbc.queryForObject("SELECT COUNT(*) FROM stock", Integer.class));
        assertEquals(assets, jdbc.queryForList("SELECT * FROM asset WHERE account_id=?", accountId));
        assertEquals(trades, jdbc.queryForList("SELECT * FROM trade_log ORDER BY trade_id"));
        assertEquals(pnl, jdbc.queryForList("SELECT * FROM realized_pnl WHERE account_id=?", accountId));
        assertEquals(Long.valueOf(1000000), jdbc.queryForObject("SELECT balance FROM account WHERE account_id=?", Long.class, accountId));
        assertEquals("CANCEL", orderMapper.getOrderByOrderId(partialOrderId).getStatus());
        assertEquals(1, myPageMapper.selectStockHoldings(accountId).size());
        assertFalse(myPageMapper.selectStockHoldings(accountId).get(0).isActive());
        Map<String, Object> period = new HashMap<>();
        period.put("accountId", accountId); period.put("startDate", "2000-01-01"); period.put("endDate", "2100-01-01");
        assertEquals(1, myPageMapper.selectRealizedProfit(period).size());
        assertEquals(2, myPageMapper.selectExecutedOrders(accountId, "2000-01-01", "2100-01-01").size());
        assertEquals(2, stockMapper.findAll().size());
        assertNull(cache.getStockByCode("TEST01"));
    }

    @Test
    public void reappearanceUpdatesNameAndKeepsOldOrdersCancelled() {
        deactivate();
        SyncResult result = snapshotService.applySnapshot(Arrays.asList(stock("TEST01", "재등장"), stock("NEW001", "신규")),
                Arrays.asList(stock("TEST02", "이름변경"), stock("TEST03", "유지")));
        assertEquals(1, result.getInsertedCount());
        assertEquals(4, result.getAfterCount());
        assertEquals("재등장", cache.getStockByCode("TEST01").getStockName());
        assertEquals("이름변경", cache.getStockByCode("TEST02").getStockName());
        assertEquals("CANCEL", orderMapper.getOrderByOrderId(partialOrderId).getStatus());
        assertTrue(orderMapper.findExecutableOrders("TEST01", "BUY", 1000).isEmpty());
    }

    @Test
    public void cancelledPartialExecutionRetainsActualFillTime() {
        jdbc.update("UPDATE trade_log SET trade_date='2026-10-16 10:11:12' WHERE order_id=?", partialOrderId);
        deactivate();
        assertNotEquals("2026-10-16 10:11:12", jdbc.queryForObject(
                "SELECT DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s') FROM stock_order WHERE order_id=?",
                String.class, partialOrderId));
        com.antmillion.mypage.dto.ExecutedOrdersDTO fill = myPageMapper
                .selectExecutedOrders(accountId, "2000-01-01", "2100-01-01").stream()
                .filter(order -> "CANCEL".equals(order.getStatus())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(2, fill.getExecutedQty());
        assertEquals("2026-10-16 10:11:12", fill.getExecutedTime());
    }

    @Test
    public void rejectsEmptyDuplicateAndExcessivelyMissingSnapshotsWithoutChanges() {
        List<Map<String, Object>> original = jdbc.queryForList("SELECT * FROM stock ORDER BY stock_code");
        assertThrows(IllegalArgumentException.class, () -> snapshotService.applySnapshot(Collections.emptyList(), Collections.singletonList(stock("TEST03", "유지"))));
        assertThrows(IllegalArgumentException.class, () -> snapshotService.applySnapshot(Collections.singletonList(stock("TEST01", "중복")), Collections.singletonList(stock("TEST01", "중복"))));
        StockSnapshotService guarded = new StockSnapshotService(syncMapper, cache, transactionManager, 0.95);
        assertThrows(IllegalStateException.class, () -> guarded.applySnapshot(Collections.singletonList(stock("TEST02", "변경")), Collections.singletonList(stock("TEST03", "유지"))));
        assertEquals(original, jdbc.queryForList("SELECT * FROM stock ORDER BY stock_code"));
        assertEquals("PARTIAL", orderMapper.getOrderByOrderId(partialOrderId).getStatus());
    }

    @Test
    public void failureAfterWritesRollsBackEverythingAndKeepsPreviousCache() {
        List<Map<String, Object>> original = jdbc.queryForList("SELECT * FROM stock ORDER BY stock_code");
        StockCache failingCache = new StockCache(stockMapper) {
            @Override public void refresh() { throw new IllegalStateException("synthetic cache read failure"); }
        };
        StockSnapshotService failing = new StockSnapshotService(syncMapper, failingCache, transactionManager, 0);
        assertThrows(IllegalStateException.class, () -> failing.applySnapshot(Collections.singletonList(stock("TEST02", "변경")), Collections.singletonList(stock("NEW001", "신규"))));
        assertEquals(original, jdbc.queryForList("SELECT * FROM stock ORDER BY stock_code"));
        assertEquals("PARTIAL", orderMapper.getOrderByOrderId(partialOrderId).getStatus());
        assertNotNull(cache.getStockByCode("TEST01"));
        assertNull(cache.getStockByCode("NEW001"));
    }

    @Test
    public void ambientRollbackDoesNotPublishUncommittedCache() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).execute(status -> {
            deactivate();
            assertNotNull(cache.getStockByCode("TEST01"));
            throw new IllegalStateException("synthetic outer failure");
        }));
        assertNotNull(cache.getStockByCode("TEST01"));
        assertEquals("PARTIAL", orderMapper.getOrderByOrderId(partialOrderId).getStatus());
    }

    @Test
    public void secondMarketDownloadFailureLeavesDatabaseUntouched() {
        KisStockMasterSource source = new KisStockMasterSource() {
            @Override public List<StockDTO> loadStocks(String market) throws IOException {
                if ("KOSDAQ".equals(market)) throw new IOException("synthetic download failure");
                return Collections.singletonList(stock("NEW001", "신규"));
            }
        };
        assertThrows(IOException.class, () -> new KisStockSyncService(source, snapshotService).syncAllStocks());
        assertNull(cache.getStockByCode("NEW001"));
        assertEquals(Integer.valueOf(3), jdbc.queryForObject("SELECT COUNT(*) FROM stock WHERE active=1", Integer.class));
        assertEquals("PARTIAL", orderMapper.getOrderByOrderId(partialOrderId).getStatus());
    }

    @Test
    public void inactiveStockCannotCreateModifyOrExecuteOrders() {
        deactivate();
        assertFalse(orderService.createOrder(accountId, "TEST01", "BUY", "LIMIT", 1, 1000));
        // 오래된 대기(WAIT) 주문을 재현하고 활성 종목 조건으로 정정·체결이 거부되는지 확인합니다.
        jdbc.update("UPDATE stock_order SET status='WAIT' WHERE order_id=?", partialOrderId);
        StockOrderDTO request = new StockOrderDTO();
        request.setOrderId(partialOrderId); request.setQuantity(10); request.setOrderPrice(1200);
        assertFalse(orderService.modifyOrder(accountId, request));
        int tradeCount = jdbc.queryForObject("SELECT COUNT(*) FROM trade_log", Integer.class);
        engine.processExecution("TEST01", "BUY", 900, 10);
        assertEquals(Integer.valueOf(tradeCount), jdbc.queryForObject("SELECT COUNT(*) FROM trade_log", Integer.class));
        assertTrue(orderService.cancelOrder(partialOrderId, accountId));
        assertTrue(orderService.createOrder(accountId, "TEST02", "BUY", "LIMIT", 1, 1000));
    }

    @Test
    public void inactiveHoldingsStayVisibleWithoutExternalPriceOrZeroValuation() {
        deactivate();
        int[] calls = {0};
        CurrentPriceProvider prices = new CurrentPriceProvider() {
            @Override public Integer getCurrentPrice(String code) {
                calls[0]++; throw new AssertionError("inactive stock must not request a price");
            }
            @Override public List<CurrentQuote> getCurrentPrices(List<String> codes) { throw new AssertionError(); }
        };
        List<StockHoldingsDTO> holdings = new MyPageServiceImpl(myPageMapper, prices).getStockHoldings(accountId);
        assertEquals(1, holdings.size());
        assertEquals(10, holdings.get(0).getShares());
        assertNull(holdings.get(0).getCurrentPrice());
        assertNull(holdings.get(0).getTotalValue());
        assertNull(holdings.get(0).getProfit());
        assertEquals(0, calls[0]);
    }

    private SyncResult deactivate() {
        return snapshotService.applySnapshot(Collections.singletonList(stock("TEST02", "유지")), Collections.singletonList(stock("TEST03", "유지")));
    }

    @Test
    public void activeQuotesKeepNormalValuesAndMissingQuotesStayUnknown() {
        for (Integer quote : Arrays.asList(null, 0, -1, 1200)) {
            CurrentPriceProvider prices = new CurrentPriceProvider() {
                @Override public Integer getCurrentPrice(String code) { return quote; }
                @Override public List<CurrentQuote> getCurrentPrices(List<String> codes) { throw new AssertionError(); }
            };
            StockHoldingsDTO holding = new MyPageServiceImpl(myPageMapper, prices).getStockHoldings(accountId).get(0);
            if (quote == null || quote <= 0) {
                assertNull(holding.getCurrentPrice());
                assertNull(holding.getTotalValue());
                assertNull(holding.getProfitRate());
            } else {
                assertEquals(Long.valueOf(1200), holding.getCurrentPrice());
                assertEquals(Long.valueOf(12000), holding.getTotalValue());
                assertEquals(Long.valueOf(2000), holding.getProfit());
                assertEquals(Double.valueOf(20.0), holding.getProfitRate());
            }
        }
    }

    private static StockDTO stock(String code, String name) {
        return StockDTO.builder().stockCode(code).stockName(name).build();
    }

    private Long order(String code, String status) {
        jdbc.update("INSERT INTO stock_order(account_id,stock_code,transaction_type,order_type,quantity,order_price,status,created_at,updated_at) VALUES(?,?,'BUY','LIMIT',10,1000,?,NOW(),NOW())", accountId, code, status);
        return jdbc.queryForObject("SELECT MAX(order_id) FROM stock_order WHERE account_id=?", Long.class, accountId);
    }
}
