package com.antmillion.stock.service;

import com.antmillion.stock.mapper.AssetMapper;
import com.antmillion.stock.dto.TradeLogDTO;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration("classpath:test-context.xml")
public class TradingExecutionAtomicityTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StockOrderMapper orderMapper;
    @Autowired private TradeLogMapper tradeMapper;
    @Autowired private AssetMapper assetMapper;
    @Autowired private RealizedPnlMapper pnlMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<Long> users = new ArrayList<>();
    private final List<Long> accounts = new ArrayList<>();
    private Long accountId;
    private int orderSequence;
    private TradingEngineService engine;

    @Before
    public void fixtures() {
        assertFalse("No test-managed transaction may mask application rollback",
                TransactionSynchronizationManager.isActualTransactionActive());
        accountId = account(1000000);
        jdbc.update("INSERT INTO stock(stock_code,stock_name) VALUES('ATOM01','합성 체결')");
        // Direct construction deliberately bypasses test-context's annotation transaction proxy.
        engine = engine(assetMapper);
    }

    @After
    public void cleanup() {
        for (Long id : accounts) jdbc.update("DELETE FROM realized_pnl WHERE account_id=?", id);
        for (Long id : users) jdbc.update("DELETE FROM member WHERE user_id=?", id);
        jdbc.update("DELETE FROM stock WHERE stock_code='ATOM01'");
    }

    @Test
    public void partialAndFullExecutionsLinkEachSellToItsGeneratedTradeAndAccount() {
        Long buy = order(accountId, "BUY", 5, 1000);
        engine.processExecution("ATOM01", 1000, 3);
        assertEquals("PARTIAL", status(buy));
        engine.processExecution("ATOM01", 1000, 2);
        assertEquals("COMPLETED", status(buy));

        Long secondAccount = account(2000);
        jdbc.update("INSERT INTO asset(account_id,stock_code,quantity,purchase_amount,avg_price) VALUES(?,'ATOM01',2,1000,500)", secondAccount);
        Long firstSell = order(accountId, "SELL", 5, 1200);
        Long secondSell = order(secondAccount, "SELL", 2, 1200);
        engine.processExecution("ATOM01", 1200, 2);
        assertEquals("PARTIAL", status(firstSell));
        assertEquals(Long.valueOf(997400), balance(accountId));
        engine.processExecution("ATOM01", 1200, 5);

        assertEquals("COMPLETED", status(firstSell));
        assertEquals("COMPLETED", status(secondSell));
        assertEquals(Long.valueOf(1001000), balance(accountId));
        assertEquals(Long.valueOf(4400), balance(secondAccount));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE stock_code='ATOM01'", Integer.class));
        List<Map<String, Object>> pnl = jdbc.queryForList(
                "SELECT p.*,t.order_id,t.trade_quantity,t.trade_price,o.account_id AS trade_account_id "
                + "FROM realized_pnl p JOIN trade_log t ON t.trade_id=p.trade_id "
                + "JOIN stock_order o ON o.order_id=t.order_id WHERE p.stock_code='ATOM01' ORDER BY p.trade_id");
        assertEquals("Every realized P&L must join to the actual sell trade", 3, pnl.size());
        assertEquals(3, pnl.stream().map(row -> row.get("trade_id")).distinct().count());
        long[] costs = {2000, 3000, 1000};
        long[] profits = {400, 600, 1400};
        for (int i = 0; i < pnl.size(); i++) {
            Map<String, Object> row = pnl.get(i);
            assertTrue(((Number) row.get("trade_id")).longValue() > 1);
            assertEquals((i < 2 ? firstSell : secondSell).longValue(), ((Number) row.get("order_id")).longValue());
            assertEquals(row.get("account_id"), row.get("trade_account_id"));
            assertEquals(((Number) row.get("sell_quantity")).intValue(), ((Number) row.get("trade_quantity")).intValue());
            assertEquals(((Number) row.get("trade_quantity")).longValue() * 1200, ((Number) row.get("sell_amount")).longValue());
            assertEquals(costs[i], ((Number) row.get("cost_basis")).longValue());
            assertEquals(profits[i], ((Number) row.get("realized_profit")).longValue());
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    public void insufficientBalanceRollsBackTradeAndOrderChanges() {
        jdbc.update("UPDATE account SET balance=500 WHERE account_id=?", accountId);
        order(accountId, "BUY", 1, 1000);
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThrows(RuntimeException.class, () -> engine.processExecution("ATOM01", 1000, 1));
        assertEquals(before, snapshot());
    }

    @Test
    public void failureAfterBuyAssetWriteRollsBackTradeOrderBalanceAndAsset() {
        order(accountId, "BUY", 3, 1000);
        assertLateFailureRollsBack("upsertAssetBuy", 1000, 3);
    }

    @Test
    public void failureAfterSellBalanceWriteRollsBackPnlTradeOrderAssetAndBalance() {
        jdbc.update("INSERT INTO asset(account_id,stock_code,quantity,purchase_amount,avg_price) VALUES(?,'ATOM01',4,4000,1000)", accountId);
        order(accountId, "SELL", 2, 1200);
        assertLateFailureRollsBack("addBalance", 1200, 2);
    }

    @Test
    public void laterOrderFailureRollsBackEarlierFillInSameInvocation() {
        Long first = order(accountId, "BUY", 1, 1000);
        Long secondAccount = account(500);
        Long second = order(secondAccount, "BUY", 1, 1000);
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThrows(RuntimeException.class, () -> engine.processExecution("ATOM01", 1000, 2));
        assertEquals(before, snapshot());
        assertEquals("WAIT", status(first));
        assertEquals("WAIT", status(second));
    }

    @Test
    public void joinsExistingTransactionAndRollsBackWithCaller() {
        order(accountId, "BUY", 1, 1000);
        Map<String, List<Map<String, Object>>> before = snapshot();
        RuntimeException failure = new IllegalStateException("synthetic caller failure");
        assertSame(failure, assertThrows(RuntimeException.class, () ->
                new TransactionTemplate(transactionManager).execute(status -> {
                    engine.processExecution("ATOM01", 1000, 1);
                    assertNotEquals(before, snapshot());
                    throw failure;
                })));
        assertEquals(before, snapshot());
    }

    @Test
    public void missingGeneratedKeyRollsBackInsertedTrade() {
        order(accountId, "BUY", 1, 1000);
        Map<String, List<Map<String, Object>>> before = snapshot();
        TradeLogMapper missingKey = (TradeLogMapper) Proxy.newProxyInstance(TradeLogMapper.class.getClassLoader(),
                new Class<?>[]{TradeLogMapper.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(tradeMapper, args);
                        if ("insertTradeLog".equals(method.getName())) ((TradeLogDTO) args[0]).setTradeId(null);
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        TradingEngineService missingKeyEngine = new TradingEngineService(orderMapper, missingKey, assetMapper, pnlMapper, transactionManager);
        assertThrows(IllegalStateException.class, () -> missingKeyEngine.processExecution("ATOM01", 1000, 1));
        assertEquals(before, snapshot());
    }

    private void assertLateFailureRollsBack(String methodName, int price, int quantity) {
        Map<String, List<Map<String, Object>>> before = snapshot();
        RuntimeException failure = new IllegalStateException("synthetic late write failure");
        AssetMapper failing = (AssetMapper) Proxy.newProxyInstance(AssetMapper.class.getClassLoader(),
                new Class<?>[]{AssetMapper.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(assetMapper, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (methodName.equals(method.getName())) {
                        assertNotEquals("Failure must follow actual DB writes", before, snapshot());
                        throw failure;
                    }
                    return result;
                });
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> engine(failing).processExecution("ATOM01", price, quantity)));
        assertEquals(before, snapshot());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private TradingEngineService engine(AssetMapper assets) {
        return new TradingEngineService(orderMapper, tradeMapper, assets, pnlMapper, transactionManager);
    }

    private Long account(long initialBalance) {
        String nickname = "atomic-" + UUID.randomUUID();
        jdbc.update("INSERT INTO member(rank_id,nickname,provider) VALUES(NULL,?,'LOCAL')", nickname);
        Long user = jdbc.queryForObject("SELECT user_id FROM member WHERE nickname=?", Long.class, nickname);
        users.add(user);
        jdbc.update("INSERT INTO account(user_id,account_number,balance) VALUES(?,?,?)", user, nickname, initialBalance);
        Long id = jdbc.queryForObject("SELECT account_id FROM account WHERE user_id=?", Long.class, user);
        accounts.add(id);
        return id;
    }

    private Long order(Long account, String type, int quantity, int price) {
        // Distinct timestamps make the existing FIFO SQL deterministic for multi-order failures.
        Timestamp created = Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(++orderSequence));
        jdbc.update("INSERT INTO stock_order(account_id,stock_code,transaction_type,order_type,quantity,order_price,status,created_at,updated_at) "
                + "VALUES(?,'ATOM01',?,'LIMIT',?,?,'WAIT',?,?)", account, type, quantity, price, created, created);
        return jdbc.queryForObject("SELECT order_id FROM stock_order WHERE account_id=? AND created_at=?", Long.class, account, created);
    }

    private String status(Long id) {
        return jdbc.queryForObject("SELECT status FROM stock_order WHERE order_id=?", String.class, id);
    }

    private Long balance(Long id) {
        return jdbc.queryForObject("SELECT balance FROM account WHERE account_id=?", Long.class, id);
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        String ids = accounts.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        state.put("account", jdbc.queryForList("SELECT * FROM account WHERE account_id IN (" + ids + ") ORDER BY account_id"));
        state.put("asset", jdbc.queryForList("SELECT * FROM asset WHERE stock_code='ATOM01' ORDER BY asset_id"));
        state.put("order", jdbc.queryForList("SELECT * FROM stock_order WHERE stock_code='ATOM01' ORDER BY order_id"));
        state.put("trade", jdbc.queryForList("SELECT t.* FROM trade_log t JOIN stock_order o ON o.order_id=t.order_id WHERE o.stock_code='ATOM01' ORDER BY t.trade_id"));
        state.put("pnl", jdbc.queryForList("SELECT * FROM realized_pnl WHERE stock_code='ATOM01' ORDER BY real_id"));
        return state;
    }
}
