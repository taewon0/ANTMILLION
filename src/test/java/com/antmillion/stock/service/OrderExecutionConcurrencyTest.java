package com.antmillion.stock.service;

import com.antmillion.stock.dto.StockOrderDTO;
import com.antmillion.stock.mapper.AssetMapper;
import com.antmillion.stock.mapper.RealizedPnlMapper;
import com.antmillion.stock.mapper.StockOrderMapper;
import com.antmillion.stock.mapper.TradeLogMapper;
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** 독립적인 DB 연결과 실제 SQL을 사용하며 애플리케이션 서비스는 스프링 프록시를 거치지 않도록 직접 생성합니다. */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration("classpath:test-context.xml")
public class OrderExecutionConcurrencyTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StockOrderMapper orders;
    @Autowired private TradeLogMapper trades;
    @Autowired private AssetMapper assets;
    @Autowired private RealizedPnlMapper pnl;
    @Autowired private PlatformTransactionManager transactionManager;
    private ExecutorService workers;
    private final CountDownLatch release = new CountDownLatch(1);
    private Long userId;
    private Long accountId;
    private Long orderId;

    @Before
    public void fixture() {
        workers = Executors.newFixedThreadPool(2);
        String name = "race-" + UUID.randomUUID();
        jdbc.update("INSERT INTO member(rank_id,nickname,provider) VALUES(NULL,?,'LOCAL')", name);
        userId = jdbc.queryForObject("SELECT user_id FROM member WHERE nickname=?", Long.class, name);
        jdbc.update("INSERT INTO account(user_id,account_number,balance) VALUES(?,?,100000)", userId, name);
        accountId = jdbc.queryForObject("SELECT account_id FROM account WHERE user_id=?", Long.class, userId);
        jdbc.update("INSERT INTO stock(stock_code,stock_name) VALUES('RACE01','합성 동시성')");
    }

    @After
    public void cleanup() throws Exception {
        release.countDown();
        workers.shutdownNow();
        assertTrue("Workers must finish before fixture cleanup", workers.awaitTermination(20, TimeUnit.SECONDS));
        jdbc.update("DELETE FROM realized_pnl WHERE account_id=?", accountId);
        jdbc.update("DELETE FROM member WHERE user_id=?", userId);
        jdbc.update("DELETE FROM stock WHERE stock_code='RACE01'");
    }

    @Test
    public void concurrentBuyCannotFillTheSameOrderTwice() throws Exception {
        concurrentExecution("BUY", 2, 2);
        assertEquals(1, countTrades());
        assertEquals(98000L, balance());
        assertEquals(2, holding());
    }

    @Test
    public void concurrentPartialBuyUsesLatestRemainingQuantity() throws Exception {
        concurrentExecution("BUY", 5, 3);
        assertEquals(2, countTrades());
        assertEquals(95000L, balance());
        assertEquals(5, holding());
    }

    @Test
    public void concurrentPartialSellUsesLatestQuantityAndCost() throws Exception {
        // 잘못된 초과 체결이 수량 검증에 먼저 막히지 않고 커밋될 수 있도록 보유 수량을 넉넉히 설정합니다.
        jdbc.update("INSERT INTO asset(account_id,stock_code,quantity,purchase_amount,avg_price) VALUES(?,'RACE01',7,5003,714.7142857)", accountId);
        concurrentExecution("SELL", 5, 3);
        assertEquals(2, countTrades());
        assertEquals(105000L, balance());
        assertEquals(2, holding());
        assertEquals(1430L, jdbc.queryForObject("SELECT purchase_amount FROM asset WHERE account_id=?", Long.class, accountId).longValue());
        assertEquals(3573L, jdbc.queryForObject("SELECT SUM(cost_basis) FROM realized_pnl WHERE account_id=?", Long.class, accountId).longValue());
    }

    @Test
    public void concurrentSellCannotFillTheSameOrderTwice() throws Exception {
        jdbc.update("INSERT INTO asset(account_id,stock_code,quantity,purchase_amount,avg_price) VALUES(?,'RACE01',4,3000,750)", accountId);
        concurrentExecution("SELL", 2, 2);
        assertEquals(1, countTrades());
        assertEquals(102000L, balance());
        assertEquals(2, holding());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM realized_pnl WHERE account_id=?", Integer.class, accountId).intValue());
    }

    @Test
    public void directCancellationRollsBackAfterUpdateFailure() {
        order("BUY", 5, 1000);
        StockOrderMapper failing = failAfter("cancelOrder");
        assertThrows(IllegalStateException.class, () -> service(failing).cancelOrder(orderId, accountId));
        assertEquals("WAIT", status());
        assertEquals(0, countTrades());
    }

    @Test
    public void directModificationRollsBackAfterUpdateFailure() {
        order("BUY", 5, 1000);
        StockOrderMapper failing = failAfter("updateOrder");
        assertThrows(IllegalStateException.class, () -> service(failing).modifyOrder(accountId, modification(1, 900)));
        assertEquals(5, jdbc.queryForObject("SELECT quantity FROM stock_order WHERE order_id=?", Integer.class, orderId).intValue());
        assertEquals(1000, jdbc.queryForObject("SELECT order_price FROM stock_order WHERE order_id=?", Integer.class, orderId).intValue());
    }

    @Test
    public void cancellationAndModificationParticipateInOuterRollback() {
        order("BUY", 5, 1000);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertTrue(service(orders).modifyOrder(accountId, modification(1, 900)));
            assertTrue(service(orders).cancelOrder(orderId, accountId));
            tx.setRollbackOnly();
        });
        assertEquals("WAIT", status());
        assertEquals(5, jdbc.queryForObject("SELECT quantity FROM stock_order WHERE order_id=?", Integer.class, orderId).intValue());
        assertEquals(1000, jdbc.queryForObject("SELECT order_price FROM stock_order WHERE order_id=?", Integer.class, orderId).intValue());
    }

    @Test
    public void rejectedStatusUpdateRollsBackTrade() {
        order("BUY", 5, 1000);
        StockOrderMapper rejecting = (StockOrderMapper) Proxy.newProxyInstance(StockOrderMapper.class.getClassLoader(),
                new Class<?>[]{StockOrderMapper.class}, (proxy, method, args) ->
                        "updateOrderStatus".equals(method.getName()) ? 0 : invoke(method, args));
        assertThrows(IllegalStateException.class, () -> engine(rejecting).processExecution("RACE01", "BUY", 1000, 2));
        assertEquals("WAIT", status());
        assertEquals(0, countTrades());
        assertEquals(100000L, balance());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE account_id=?", Integer.class, accountId).intValue());
    }

    @Test
    public void cancellationAfterCandidateReadPreventsExecution() throws Exception {
        order("BUY", 5, 1000);
        CountDownLatch selected = new CountDownLatch(1);
        Future<?> execution = pausedExecution(selected);
        try {
            await(selected);
            assertTrue(service(orders).cancelOrder(orderId, accountId));
        } finally {
            release.countDown();
        }
        execution.get(15, TimeUnit.SECONDS);
        assertEquals("CANCEL", status());
        assertEquals(0, countTrades());
        assertEquals(100000L, balance());
    }

    @Test
    public void priceModificationAfterCandidateReadPreventsExecution() throws Exception {
        order("BUY", 5, 1000);
        CountDownLatch selected = new CountDownLatch(1);
        Future<?> execution = pausedExecution(selected);
        try {
            await(selected);
            assertTrue(service(orders).modifyOrder(accountId, modification(5, 900)));
        } finally {
            release.countDown();
        }
        execution.get(15, TimeUnit.SECONDS);
        assertEquals("WAIT", status());
        assertEquals(0, countTrades());
        assertEquals(100000L, balance());
    }

    @Test
    public void quantityModificationAfterCandidateReadLimitsExecution() throws Exception {
        order("BUY", 5, 1000);
        CountDownLatch selected = new CountDownLatch(1);
        Future<?> execution = pausedExecution(selected);
        try {
            await(selected);
            assertTrue(service(orders).modifyOrder(accountId, modification(1, 1000)));
        } finally {
            release.countDown();
        }
        execution.get(15, TimeUnit.SECONDS);
        assertEquals(1, executed());
        assertEquals("COMPLETED", status());
        assertEquals(99000L, balance());
    }

    @Test
    public void completedExecutionWinsAgainstCancellation() throws Exception {
        executionWins(false);
    }

    @Test
    public void partialExecutionWinsAgainstModification() throws Exception {
        executionWins(true);
    }

    private void executionWins(boolean modify) throws Exception {
        order("BUY", modify ? 5 : 2, 1000);
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch requested = new CountDownLatch(1);
        StockOrderMapper executing = intercept((method, args, result) -> {
            if ("updateOrderStatus".equals(method)) {
                updated.countDown();
                await(release);
            }
        });
        Future<?> execution = workers.submit(() -> engine(executing).processExecution("RACE01", "BUY", 1000, 2));
        await(updated);
        StockOrderMapper requesting = (StockOrderMapper) Proxy.newProxyInstance(StockOrderMapper.class.getClassLoader(),
                new Class<?>[]{StockOrderMapper.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("getOrderByOrderId")) requested.countDown();
                    return invoke(method, args);
                });
        Future<?> request = workers.submit(() -> {
            StockOrderService service = service(requesting);
            if (modify) {
                assertThrows(IllegalArgumentException.class, () -> service.modifyOrder(accountId, modification(1, 900)));
            } else {
                assertThrows(IllegalStateException.class, () -> service.cancelOrder(orderId, accountId));
            }
        });
        try { await(requested); } finally { release.countDown(); }
        execution.get(15, TimeUnit.SECONDS);
        request.get(15, TimeUnit.SECONDS);
        assertEquals(modify ? "PARTIAL" : "COMPLETED", status());
        assertEquals(2, executed());
        assertEquals(modify ? 5 : 2, jdbc.queryForObject("SELECT quantity FROM stock_order WHERE order_id=?", Integer.class, orderId).intValue());
    }

    private void concurrentExecution(String direction, int quantity, int available) throws Exception {
        order(direction, quantity, 1000);
        CountDownLatch selected = new CountDownLatch(2);
        StockOrderMapper paused = intercept((method, args, result) -> {
            if ("findExecutableOrders".equals(method)) {
                selected.countDown();
                await(release);
            }
        });
        Future<?> first = workers.submit(() -> engine(paused).processExecution("RACE01", direction, 1000, available));
        Future<?> second = workers.submit(() -> engine(paused).processExecution("RACE01", direction, 1000, available));
        try { await(selected); } finally { release.countDown(); }
        first.get(15, TimeUnit.SECONDS);
        second.get(15, TimeUnit.SECONDS);
        assertEquals(quantity, executed());
        assertEquals("COMPLETED", status());
    }

    private Future<?> pausedExecution(CountDownLatch selected) {
        StockOrderMapper paused = intercept((method, args, result) -> {
            if ("findExecutableOrders".equals(method)) {
                selected.countDown();
                await(release);
            }
        });
        return workers.submit(() -> engine(paused).processExecution("RACE01", "BUY", 1000, 5));
    }

    private interface AfterQuery { void run(String method, Object[] args, Object result) throws Exception; }

    private StockOrderMapper failAfter(String operation) {
        return intercept((method, args, result) -> {
            if (operation.equals(method)) throw new IllegalStateException("Forced failure after SQL update");
        });
    }

    private StockOrderMapper intercept(AfterQuery hook) {
        return (StockOrderMapper) Proxy.newProxyInstance(StockOrderMapper.class.getClassLoader(),
                new Class<?>[]{StockOrderMapper.class}, (proxy, method, args) -> {
                    Object result = invoke(method, args);
                    hook.run(method.getName(), args, result);
                    return result;
                });
    }

    private Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(orders, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    private TradingEngineService engine(StockOrderMapper mapper) {
        return new TradingEngineService(mapper, trades, assets, pnl, transactionManager);
    }

    private StockOrderService service(StockOrderMapper mapper) {
        return new StockOrderService(mapper, trades, null, assets, transactionManager);
    }

    private StockOrderDTO modification(int quantity, int price) {
        StockOrderDTO dto = new StockOrderDTO();
        dto.setOrderId(orderId);
        dto.setQuantity(quantity);
        dto.setOrderPrice(price);
        return dto;
    }

    private void order(String direction, int quantity, int price) {
        jdbc.update("INSERT INTO stock_order(account_id,stock_code,transaction_type,order_type,quantity,order_price,status,created_at,updated_at) "
                + "VALUES(?,'RACE01',?,'LIMIT',?,?,'WAIT',NOW(),NOW())", accountId, direction, quantity, price);
        orderId = jdbc.queryForObject("SELECT order_id FROM stock_order WHERE account_id=?", Long.class, accountId);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("Timed out coordinating independent DB transactions", latch.await(10, TimeUnit.SECONDS));
    }

    private int executed() { return trades.getTotalExecutedQty(orderId); }
    private int countTrades() { return jdbc.queryForObject("SELECT COUNT(*) FROM trade_log WHERE order_id=?", Integer.class, orderId); }
    private String status() { return jdbc.queryForObject("SELECT status FROM stock_order WHERE order_id=?", String.class, orderId); }
    private long balance() { return jdbc.queryForObject("SELECT balance FROM account WHERE account_id=?", Long.class, accountId); }
    private int holding() { return jdbc.queryForObject("SELECT quantity FROM asset WHERE account_id=?", Integer.class, accountId); }
}
