package com.antmillion.stock.service;

import com.antmillion.kis.handler.KisWebSocketHandler;
import com.antmillion.marketdata.service.RealtimeMarketDataService;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.TextMessage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
        // 테스트 컨텍스트의 애노테이션 트랜잭션 프록시를 거치지 않도록 직접 생성합니다.
        engine = engine(assetMapper);
    }

    @After
    public void cleanup() {
        for (Long id : accounts) jdbc.update("DELETE FROM realized_pnl WHERE account_id=?", id);
        for (Long id : users) jdbc.update("DELETE FROM member WHERE user_id=?", id);
        jdbc.update("DELETE FROM stock WHERE stock_code='ATOM01'");
    }

    @Test
    public void missingOrUnknownDirectionCannotMutateOrders() {
        order(accountId, "BUY", 1, 1100);
        Map<String, List<Map<String, Object>>> before = snapshot();
        for (String direction : Arrays.asList(null, "", "buy", "ASK")) {
            assertThrows(IllegalArgumentException.class,
                    () -> engine.processExecution("ATOM01", direction, 1100, 1));
            assertEquals(before, snapshot());
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    public void askLiquidityDoesNotFillEarlierSellOrder() throws Exception {
        Long seller = seller(2);
        Long sell = order(seller, "SELL", 2, 1000);
        Long buy = order(accountId, "BUY", 2, 1100);

        quote("H0STASP0", 1100, 2, 1000, 0);

        assertEquals("WAIT", status(sell));
        assertEquals("COMPLETED", status(buy));
        assertTrade(buy, 1100, 2);
        assertEquals(Long.valueOf(1000000), balance(seller));
        assertEquals(Integer.valueOf(2), assetMapper.getQuantityByAccountAndStock(seller, "ATOM01"));
    }

    @Test
    public void bidLiquidityDoesNotFillEarlierBuyOrder() throws Exception {
        Long buy = order(accountId, "BUY", 2, 1100);
        Long seller = seller(2);
        Long sell = order(seller, "SELL", 2, 1000);

        quote("H0STASP0", 1100, 0, 1000, 2);

        assertEquals("WAIT", status(buy));
        assertEquals("COMPLETED", status(sell));
        assertTrade(sell, 1000, 2);
        assertEquals(Long.valueOf(1000000), balance(accountId));
        assertNull(assetMapper.getAssetForSell(accountId, "ATOM01"));
    }

    @Test
    public void krxQuoteUsesSeparatePricesQuantitiesAndFifoForEachDirection() throws Exception {
        assertQuoteDirections("H0STASP0");
    }

    @Test
    public void unifiedQuoteUsesSeparatePricesQuantitiesAndFifoForEachDirection() throws Exception {
        assertQuoteDirections("H0UNASP0");
    }

    private void assertQuoteDirections(String trId) throws Exception {
        Long seller = seller(5);
        Long firstSell = order(seller, "SELL", 2, 1000);
        Long firstBuy = order(accountId, "BUY", 3, 1100);
        Long secondSell = order(seller, "SELL", 3, 1000);
        Long secondBuy = order(accountId, "BUY", 2, 1100);
        Long belowAskBuy = order(accountId, "BUY", 1, 1099);
        Long aboveBidSell = order(seller, "SELL", 1, 1001);

        quote(trId, 1100, 4, 1000, 1);
        assertEquals("COMPLETED", status(firstBuy));
        assertEquals("PARTIAL", status(secondBuy));
        assertEquals("PARTIAL", status(firstSell));
        assertEquals("WAIT", status(secondSell));
        assertTrade(firstBuy, 1100, 3);
        assertTrade(secondBuy, 1100, 1);
        assertTrade(firstSell, 1000, 1);

        quote(trId, 1100, 1, 1000, 4);
        for (Long id : Arrays.asList(firstBuy, secondBuy, firstSell, secondSell)) {
            assertEquals("COMPLETED", status(id));
        }
        assertEquals("WAIT", status(belowAskBuy));
        assertEquals("WAIT", status(aboveBidSell));
        assertTrade(firstBuy, 1100, 3);
        assertTrade(secondBuy, 1100, 2);
        assertTrade(firstSell, 1000, 2);
        assertTrade(secondSell, 1000, 3);
        assertEquals(Long.valueOf(994500), balance(accountId));
        assertEquals(Long.valueOf(1005000), balance(seller));
        assertEquals(Integer.valueOf(5), assetMapper.getQuantityByAccountAndStock(accountId, "ATOM01"));
        assertNull(assetMapper.getAssetForSell(seller, "ATOM01"));
        assertEquals(Long.valueOf(5000), jdbc.queryForObject(
                "SELECT SUM(sell_amount) FROM realized_pnl WHERE account_id=?", Long.class, seller));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    public void krxMarketOrdersUseOppositeQuotesRegardlessOfSubmittedPrice() throws Exception {
        assertMarketQuoteExecutions("H0STASP0");
    }

    @Test
    public void unifiedMarketOrdersUseOppositeQuotesRegardlessOfSubmittedPrice() throws Exception {
        assertMarketQuoteExecutions("H0UNASP0");
    }

    private void assertMarketQuoteExecutions(String trId) throws Exception {
        Long seller = seller(3);
        Long buy = order(accountId, "BUY", "MARKET", 3, 1000);
        Long sell = order(seller, "SELL", "MARKET", 3, 1200);

        quote(trId, 1100, 2, 1000, 1);
        assertEquals("PARTIAL", status(buy));
        assertEquals("PARTIAL", status(sell));
        assertTrade(buy, 1100, 2);
        assertTrade(sell, 1000, 1);
        assertEquals(Long.valueOf(997800), balance(accountId));
        assertEquals(Long.valueOf(1001000), balance(seller));

        // 이후 호가에서는 남은 수량이 다른 가격으로 체결될 수 있습니다.
        quote(trId, 1200, 1, 900, 2);
        assertEquals("COMPLETED", status(buy));
        assertEquals("COMPLETED", status(sell));
        assertEquals(Arrays.asList(1100L, 1200L), jdbc.queryForList(
                "SELECT trade_price FROM trade_log WHERE order_id=? ORDER BY trade_id", Long.class, buy));
        assertEquals(Arrays.asList(1000L, 900L), jdbc.queryForList(
                "SELECT trade_price FROM trade_log WHERE order_id=? ORDER BY trade_id", Long.class, sell));
        assertEquals(Long.valueOf(996600), balance(accountId));
        assertEquals(Long.valueOf(1002800), balance(seller));
        assertEquals(Integer.valueOf(3), assetMapper.getQuantityByAccountAndStock(accountId, "ATOM01"));
        assertEquals(Long.valueOf(3400), assetMapper.getAssetForSell(accountId, "ATOM01").getPurchaseAmount());
        assertNull(assetMapper.getAssetForSell(seller, "ATOM01"));
        Map<String, Object> pnl = jdbc.queryForMap(
                "SELECT SUM(p.sell_amount) AS amount,SUM(p.cost_basis) AS cost,SUM(p.realized_profit) AS profit,"
                + "COUNT(*) AS trades FROM realized_pnl p JOIN trade_log t ON t.trade_id=p.trade_id "
                + "WHERE t.order_id=? AND p.account_id=?", sell, seller);
        assertEquals(2800L, ((Number) pnl.get("amount")).longValue());
        assertEquals(2700L, ((Number) pnl.get("cost")).longValue());
        assertEquals(100L, ((Number) pnl.get("profit")).longValue());
        assertEquals(2L, ((Number) pnl.get("trades")).longValue());
        assertEquals(Integer.valueOf(1000), orderMapper.getOrderByOrderId(buy).getOrderPrice());
        assertEquals(Integer.valueOf(1200), orderMapper.getOrderByOrderId(sell).getOrderPrice());
    }

    @Test
    public void marketAndLimitOrdersShareExistingFifoWithoutBypassingLimitPrices() throws Exception {
        Long seller = seller(4);
        Long blockedBuy = order(accountId, "BUY", 1, 1099);
        Long blockedSell = order(seller, "SELL", 1, 1001);
        Long firstBuy = order(accountId, "BUY", 1, 1100);
        Long firstSell = order(seller, "SELL", 1, 1000);
        Long marketBuy = order(accountId, "BUY", "MARKET", 2, 1000);
        Long marketSell = order(seller, "SELL", "MARKET", 2, 1200);
        Long lastBuy = order(accountId, "BUY", 1, 1100);
        Long lastSell = order(seller, "SELL", 1, 1000);

        quote("H0STASP0", 1100, 2, 1000, 2);
        for (Long id : Arrays.asList(firstBuy, firstSell)) assertEquals("COMPLETED", status(id));
        for (Long id : Arrays.asList(marketBuy, marketSell)) assertEquals("PARTIAL", status(id));
        for (Long id : Arrays.asList(blockedBuy, blockedSell, lastBuy, lastSell)) assertEquals("WAIT", status(id));

        quote("H0STASP0", 1100, 2, 1000, 2);
        for (Long id : Arrays.asList(firstBuy, marketBuy, lastBuy)) {
            assertEquals("COMPLETED", status(id));
            assertTrade(id, 1100, id.equals(marketBuy) ? 2 : 1);
        }
        for (Long id : Arrays.asList(firstSell, marketSell, lastSell)) {
            assertEquals("COMPLETED", status(id));
            assertTrade(id, 1000, id.equals(marketSell) ? 2 : 1);
        }
        assertEquals("WAIT", status(blockedBuy));
        assertEquals("WAIT", status(blockedSell));
        assertEquals(Long.valueOf(995600), balance(accountId));
        assertEquals(Long.valueOf(1004000), balance(seller));
    }

    @Test
    public void nonPositivePricesCannotMutateMarketOrLimitOrders() {
        assertInvalidQuoteDoesNotMutateOrders(true);
    }

    @Test
    public void nonPositiveLiquidityCannotMutateMarketOrLimitOrders() {
        assertInvalidQuoteDoesNotMutateOrders(false);
    }

    private void assertInvalidQuoteDoesNotMutateOrders(boolean invalidPrice) {
        Long seller = seller(4);
        for (String type : Arrays.asList("LIMIT", "MARKET")) {
            order(accountId, "BUY", type, 1, 1100);
            order(seller, "SELL", type, 1, 1000);
        }
        Map<String, List<Map<String, Object>>> before = snapshot();
        for (String direction : Arrays.asList("BUY", "SELL")) {
            for (int invalid : new int[]{0, -1}) {
                engine.processExecution("ATOM01", direction, invalidPrice ? invalid : 1050, invalidPrice ? 4 : invalid);
                assertEquals(before, snapshot());
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            }
        }
    }

    @Test
    public void zeroAskDoesNotBlockValidMarketSell() throws Exception {
        Long seller = seller(1);
        Long buy = order(accountId, "BUY", "MARKET", 1, 1000);
        Long sell = order(seller, "SELL", "MARKET", 1, 1200);
        quote("H0STASP0", 0, 1, 1000, 1);
        assertEquals("WAIT", status(buy));
        assertEquals(Long.valueOf(1000000), balance(accountId));
        assertNull(assetMapper.getAssetForSell(accountId, "ATOM01"));
        assertEquals("COMPLETED", status(sell));
        assertTrade(sell, 1000, 1);
    }

    @Test
    public void zeroBidDoesNotBlockValidMarketBuy() throws Exception {
        Long seller = seller(1);
        Long buy = order(accountId, "BUY", "MARKET", 1, 1000);
        Long sell = order(seller, "SELL", "MARKET", 1, 1200);
        quote("H0UNASP0", 1100, 1, 0, 1);
        assertEquals("COMPLETED", status(buy));
        assertTrade(buy, 1100, 1);
        assertEquals("WAIT", status(sell));
        assertEquals(Long.valueOf(1000000), balance(seller));
        assertEquals(Integer.valueOf(1), assetMapper.getQuantityByAccountAndStock(seller, "ATOM01"));
    }

    @Test
    public void laterMarketBuyWithInsufficientBalanceAtQuotePriceRollsBackWholeInvocation() {
        Long first = order(accountId, "BUY", 1, 1100);
        Long secondAccount = account(1050);
        Long second = order(secondAccount, "BUY", "MARKET", 1, 1000);
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThrows(RuntimeException.class, () -> engine.processExecution("ATOM01", "BUY", 1100, 2));
        assertEquals(before, snapshot());
        assertEquals("WAIT", status(first));
        assertEquals("WAIT", status(second));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    public void marketSellWithInsufficientHoldingRollsBackAtQuotePrice() {
        holding(1, 900);
        order(accountId, "SELL", "MARKET", 2, 1200);
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThrows(IllegalStateException.class, () -> engine.processExecution("ATOM01", "SELL", 1000, 2));
        assertEquals(before, snapshot());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private Long seller(int quantity) {
        Long seller = account(1000000);
        jdbc.update("INSERT INTO asset(account_id,stock_code,quantity,purchase_amount,avg_price) "
                + "VALUES(?,'ATOM01',?,?,900)", seller, quantity, quantity * 900);
        return seller;
    }

    private void assertTrade(Long order, int price, int quantity) {
        Map<String, Object> trade = jdbc.queryForMap(
                "SELECT MIN(trade_price) AS min_price,MAX(trade_price) AS max_price,SUM(trade_quantity) AS qty "
                + "FROM trade_log WHERE order_id=?", order);
        assertEquals(price, ((Number) trade.get("min_price")).intValue());
        assertEquals(price, ((Number) trade.get("max_price")).intValue());
        assertEquals(quantity, ((Number) trade.get("qty")).intValue());
    }

    private void quote(String trId, int askPrice, int askQty, int bidPrice, int bidQty) throws Exception {
        String[] fields = new String[38];
        Arrays.fill(fields, "0");
        fields[0] = "ATOM01";
        fields[3] = String.valueOf(askPrice);
        fields[13] = String.valueOf(bidPrice);
        fields[23] = String.valueOf(askQty);
        fields[33] = String.valueOf(bidQty);
        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
        List<org.springframework.messaging.Message<?>> broadcasts = new ArrayList<>();
        channel.subscribe(broadcasts::add);
        // 합성 텍스트 프레임과 메모리 STOMP 채널만 사용하며 외부 웹소켓 관리자는 시작하지 않습니다.
        KisWebSocketHandler handler = new KisWebSocketHandler(null, null,
                new RealtimeMarketDataService(new SimpMessagingTemplate(channel), engine), 0);
        handler.handleMessage(null, new TextMessage("0|" + trId + "|001|" + String.join("^", fields)));
        assertEquals(1, broadcasts.size());
        Map<?, ?> payload = (Map<?, ?>) broadcasts.get(0).getPayload();
        assertEquals("ATOM01", payload.get("mkscShrnIscd"));
        assertEquals(String.valueOf(askPrice), payload.get("askP1"));
        assertEquals(String.valueOf(bidPrice), payload.get("bidP1"));
        assertEquals("/topic/kis-trade/ask-bidATOM01", broadcasts.get(0).getHeaders().get("simpDestination"));
    }

    @Test
    public void partialAndFullExecutionsLinkEachSellToItsGeneratedTradeAndAccount() {
        Long buy = order(accountId, "BUY", 5, 1000);
        engine.processExecution("ATOM01", "BUY", 1000, 3);
        assertEquals("PARTIAL", status(buy));
        engine.processExecution("ATOM01", "BUY", 1000, 2);
        assertEquals("COMPLETED", status(buy));

        Long secondAccount = account(2000);
        jdbc.update("INSERT INTO asset(account_id,stock_code,quantity,purchase_amount,avg_price) VALUES(?,'ATOM01',2,1000,500)", secondAccount);
        Long firstSell = order(accountId, "SELL", 5, 1200);
        Long secondSell = order(secondAccount, "SELL", 2, 1200);
        engine.processExecution("ATOM01", "SELL", 1200, 2);
        assertEquals("PARTIAL", status(firstSell));
        assertEquals(Long.valueOf(997400), balance(accountId));
        engine.processExecution("ATOM01", "SELL", 1200, 5);

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
        assertThrows(RuntimeException.class, () -> engine.processExecution("ATOM01", "BUY", 1000, 1));
        assertEquals(before, snapshot());
    }

    @Test
    public void missingHoldingRollsBackSell() {
        order(accountId, "SELL", 1, 1200);
        assertSellFailureRollsBack(engine, 1);
    }

    @Test
    public void insufficientHoldingRollsBackSell() {
        holding(1, 1000);
        order(accountId, "SELL", 2, 1200);
        assertSellFailureRollsBack(engine, 2);
    }

    @Test
    public void zeroHoldingRollsBackSell() {
        holding(0, 0);
        order(accountId, "SELL", 1, 1200);
        assertSellFailureRollsBack(engine, 1);
    }

    @Test
    public void nullHoldingQuantityRollsBackSell() {
        holding(1, 1000);
        jdbc.update("UPDATE asset SET quantity=NULL WHERE account_id=?", accountId);
        order(accountId, "SELL", 1, 1200);
        assertSellFailureRollsBack(engine, 1);
    }

    @Test
    public void zeroRowAssetDeductionRollsBackSellWithoutCreditingBalance() {
        assertZeroRowSellRollsBack("updateAssetSellWithCostBasis");
    }

    @Test
    public void zeroRowBalanceCreditRollsBackSell() {
        assertZeroRowSellRollsBack("addBalance");
    }

    @Test
    public void missingHoldingOnLaterSellRollsBackEarlierSellInSameInvocation() {
        holding(1, 1000);
        Long first = order(accountId, "SELL", 1, 1200);
        Long secondAccount = account(2000);
        Long second = order(secondAccount, "SELL", 1, 1200);
        assertSellFailureRollsBack(engine, 2);
        assertEquals("WAIT", status(first));
        assertEquals("WAIT", status(second));
    }

    @Test
    public void partialSellRoundsDownCostAndFullSellConsumesRemainingCost() {
        holding(3, 1000);
        Long sell = order(accountId, "SELL", 3, 1200);
        engine.processExecution("ATOM01", "SELL", 1200, 1);
        assertEquals("PARTIAL", status(sell));
        assertEquals(Long.valueOf(1001200), balance(accountId));
        assertEquals(Integer.valueOf(2), assetMapper.getQuantityByAccountAndStock(accountId, "ATOM01"));
        assertEquals(Long.valueOf(667), assetMapper.getAssetForSell(accountId, "ATOM01").getPurchaseAmount());

        engine.processExecution("ATOM01", "SELL", 1200, 2);
        assertEquals("COMPLETED", status(sell));
        assertEquals(Long.valueOf(1003600), balance(accountId));
        assertNull(assetMapper.getAssetForSell(accountId, "ATOM01"));
        List<Map<String, Object>> pnl = jdbc.queryForList(
                "SELECT p.*,t.order_id,t.trade_quantity FROM realized_pnl p "
                + "JOIN trade_log t ON t.trade_id=p.trade_id WHERE p.account_id=? ORDER BY p.trade_id", accountId);
        assertEquals(2, pnl.size());
        long[] costs = {333, 667};
        long[] profits = {867, 1733};
        for (int i = 0; i < pnl.size(); i++) {
            Map<String, Object> row = pnl.get(i);
            assertEquals(sell.longValue(), ((Number) row.get("order_id")).longValue());
            assertEquals(i + 1, ((Number) row.get("trade_quantity")).intValue());
            assertEquals(i + 1, ((Number) row.get("sell_quantity")).intValue());
            assertEquals(costs[i], ((Number) row.get("cost_basis")).longValue());
            assertEquals(profits[i], ((Number) row.get("realized_profit")).longValue());
            assertEquals(333.33, ((Number) row.get("avg_price")).doubleValue(), 0.001);
        }
    }

    @Test
    public void failureAfterBuyAssetWriteRollsBackTradeOrderBalanceAndAsset() {
        order(accountId, "BUY", 3, 1000);
        assertLateFailureRollsBack("upsertAssetBuy", "BUY", 1000, 3);
    }

    @Test
    public void failureAfterSellBalanceWriteRollsBackPnlTradeOrderAssetAndBalance() {
        jdbc.update("INSERT INTO asset(account_id,stock_code,quantity,purchase_amount,avg_price) VALUES(?,'ATOM01',4,4000,1000)", accountId);
        order(accountId, "SELL", 2, 1200);
        assertLateFailureRollsBack("addBalance", "SELL", 1200, 2);
    }

    @Test
    public void laterOrderFailureRollsBackEarlierFillInSameInvocation() {
        Long first = order(accountId, "BUY", 1, 1000);
        Long secondAccount = account(500);
        Long second = order(secondAccount, "BUY", 1, 1000);
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThrows(RuntimeException.class, () -> engine.processExecution("ATOM01", "BUY", 1000, 2));
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
                    engine.processExecution("ATOM01", "BUY", 1000, 1);
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
        assertThrows(IllegalStateException.class, () -> missingKeyEngine.processExecution("ATOM01", "BUY", 1000, 1));
        assertEquals(before, snapshot());
    }

    private void assertLateFailureRollsBack(String methodName, String transactionType, int price, int quantity) {
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
                () -> engine(failing).processExecution("ATOM01", transactionType, price, quantity)));
        assertEquals(before, snapshot());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private void holding(int quantity, long purchaseAmount) {
        jdbc.update("INSERT INTO asset(account_id,stock_code,quantity,purchase_amount,avg_price) "
                + "VALUES(?,'ATOM01',?,?,333.33)", accountId, quantity, purchaseAmount);
    }

    private void assertSellFailureRollsBack(TradingEngineService service, int quantity) {
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThrows(IllegalStateException.class, () -> service.processExecution("ATOM01", "SELL", 1200, quantity));
        assertEquals(before, snapshot());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private void assertZeroRowSellRollsBack(String failingMethod) {
        holding(2, 1000);
        order(accountId, "SELL", 1, 1200);
        AssetMapper zeroRows = (AssetMapper) Proxy.newProxyInstance(AssetMapper.class.getClassLoader(),
                new Class<?>[]{AssetMapper.class}, (proxy, method, args) -> {
                    if ("updateAssetSellWithCostBasis".equals(failingMethod) && "addBalance".equals(method.getName())) {
                        fail("A rejected asset deduction must not credit the balance");
                    }
                    boolean rejected = failingMethod.equals(method.getName());
                    if (rejected && "updateAssetSellWithCostBasis".equals(failingMethod)) {
                        // 정상 보유 조회 후 실제 조건부 갱신의 영향 행 수가 0이 되도록 구성합니다.
                        jdbc.update("UPDATE asset SET quantity=0 WHERE account_id=?", accountId);
                    }
                    Object[] actualArgs = rejected && "addBalance".equals(failingMethod)
                            ? new Object[]{-1L, args[1]} : args;
                    try {
                        Object result = method.invoke(assetMapper, actualArgs);
                        if (rejected) assertEquals("Real SQL must report zero affected rows", 0, result);
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        assertSellFailureRollsBack(engine(zeroRows), 1);
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
        return order(account, type, "LIMIT", quantity, price);
    }

    private Long order(Long account, String type, String orderType, int quantity, int price) {
        // 여러 주문의 실패 검증에서 기존 선입선출 SQL의 순서가 일정하도록 생성 시각을 다르게 설정합니다.
        Timestamp created = Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(++orderSequence));
        jdbc.update("INSERT INTO stock_order(account_id,stock_code,transaction_type,order_type,quantity,order_price,status,created_at,updated_at) "
                + "VALUES(?,'ATOM01',?,?,?,?,'WAIT',?,?)", account, type, orderType, quantity, price, created, created);
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
