package com.antmillion.kis;

import com.antmillion.kis.handler.KisWebSocketHandler;
import com.antmillion.kis.manager.KisSubscriptionRegistry;
import com.antmillion.stock.dto.StockDTO;
import com.antmillion.stock.mapper.StockOrderMapper;
import com.antmillion.stock.scheduler.OrderQuoteSubscriptionScheduler;
import com.antmillion.stock.scheduler.OrderScheduler;
import com.antmillion.stock.service.StockOrderService;
import com.antmillion.stock.service.StockSnapshotService;
import com.antmillion.stock.service.TradingEngineService;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/** Guarded disposable MySQL, actual Mapper/engine/STOMP events, synthetic KIS transport only. */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration("classpath:stock-preservation-context.xml")
public class OrderQuoteSubscriptionIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StockOrderMapper orders;
    @Autowired private StockOrderService orderService;
    @Autowired private StockSnapshotService snapshotService;
    @Autowired private TradingEngineService engine;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<Long> users = new ArrayList<>();
    private Long buyer;
    private Long seller;
    private KisSubscriptionRegistryTest.Wire wire;
    private OrderQuoteSubscriptionSchedulerTest.Worker worker;
    private OrderQuoteSubscriptionScheduler scheduler;

    @Before
    public void fixtures() {
        buyer = account();
        seller = account();
        for (String code : List.of("QS0001", "QS0002", "QS0003", "QS0004", "QS0005")) {
            jdbc.update("INSERT INTO stock(stock_code,stock_name) VALUES(?,?)", code, "합성 " + code);
        }
        wire = new KisSubscriptionRegistryTest.Wire();
        worker = new OrderQuoteSubscriptionSchedulerTest.Worker();
        scheduler = newScheduler(orders);
    }

    @After
    public void cleanup() {
        if (scheduler != null) scheduler.stop();
        if (wire != null) wire.close();
        for (Long id : users) {
            jdbc.update("DELETE FROM realized_pnl WHERE account_id IN (SELECT account_id FROM account WHERE user_id=?)", id);
            jdbc.update("DELETE FROM member WHERE user_id=?", id);
        }
        jdbc.update("DELETE FROM stock WHERE stock_code IN ('QS0001','QS0002','QS0003','QS0004','QS0005')");
    }

    @Test
    public void newOrdersKeepReceivingQuotesAndFillAfterLastBrowserLeaves() throws Exception {
        scheduler.start();
        worker.task.run();
        assertTrue(wire.provider.acquired.isEmpty());
        Long buy = create(buyer, "BUY", 3, 1100);
        jdbc.update("INSERT INTO asset(account_id,stock_code,quantity,purchase_amount,avg_price) VALUES(?,'QS0001',2,1800,900)", seller);
        Long sell = create(seller, "SELL", 2, 1000);
        worker.task.run();
        assertEquals("The server must acquire quotes before any browser subscribes", 1, wire.provider.acquired.size());
        WebSocketSession browser = wire.connect("logged-in-browser");
        wire.subscribe(browser, "book", "ask-bidQS0001");
        worker.task.run();
        wire.disconnect(browser);
        assertEquals(1, wire.provider.acquired.size());
        assertTrue(wire.provider.released.isEmpty());

        quote(1100, 1, 1000, 1);
        assertEquals("PARTIAL", status(buy));
        assertEquals("PARTIAL", status(sell));
        worker.task.run();
        assertTrue(wire.provider.released.isEmpty());
        quote(1100, 2, 1000, 1);
        assertEquals("COMPLETED", status(buy));
        assertEquals("COMPLETED", status(sell));
        worker.task.run();
        assertEquals(wire.provider.acquired, wire.provider.released);
        assertEquals(Long.valueOf(96700), jdbc.queryForObject("SELECT balance FROM account WHERE account_id=?", Long.class, buyer));
        assertEquals(Long.valueOf(102000), jdbc.queryForObject("SELECT balance FROM account WHERE account_id=?", Long.class, seller));
        assertEquals(Integer.valueOf(3), jdbc.queryForObject("SELECT quantity FROM asset WHERE account_id=?", Integer.class, buyer));
        assertEquals(Long.valueOf(200), jdbc.queryForObject("SELECT SUM(realized_profit) FROM realized_pnl WHERE account_id=?", Long.class, seller));
    }

    @Test
    public void oneOfSeveralAccountsCancellingCannotReleaseSharedStock() {
        Long first = create(buyer, "BUY", 1, 1100);
        Long second = create(seller, "BUY", 1, 1100);
        scheduler.start();
        worker.task.run();
        assertTrue(orderService.cancelOrder(first, buyer));
        worker.task.run();
        assertTrue(wire.provider.released.isEmpty());
        assertTrue(orderService.cancelOrder(second, seller));
        worker.task.run();
        assertEquals(1, wire.provider.acquired.size());
        assertEquals(wire.provider.acquired, wire.provider.released);
    }

    @Test
    public void morningAutomaticCancellationReleasesServerReference() {
        Long order = create(buyer, "BUY", 2, 1100);
        engine.processExecution("QS0001", "BUY", 1100, 1);
        scheduler.start();
        worker.task.run();
        assertEquals("PARTIAL", status(order));
        new OrderScheduler(orders).cancelOldOrders();
        worker.task.run();
        assertEquals("CANCEL", status(order));
        assertEquals(wire.provider.acquired, wire.provider.released);
    }

    @Test
    public void stockSnapshotDeactivationAndCancellationReleaseServerReference() {
        Long order = create(buyer, "BUY", 1, 1100);
        scheduler.start();
        worker.task.run();
        snapshotService.applySnapshot(List.of(stock("QS0002")), List.of(stock("QS0003")));
        worker.task.run();
        assertEquals("CANCEL", status(order));
        assertEquals(wire.provider.acquired, wire.provider.released);
    }

    @Test
    public void startupRestoresWaitAndPartialButIgnoresTerminalInactiveAndFullyFilledOrders() {
        fixtureOrder("QS0001", "WAIT", 2);
        Long partial = fixtureOrder("QS0001", "PARTIAL", 2);
        jdbc.update("INSERT INTO trade_log(order_id,trade_price,trade_quantity) VALUES(?,1000,1)", partial);
        fixtureOrder("QS0002", "COMPLETED", 1);
        fixtureOrder("QS0002", "CANCEL", 1);
        fixtureOrder("QS0003", "WAIT", 1);
        jdbc.update("UPDATE stock SET active=0 WHERE stock_code='QS0003'");
        Long fullyFilled = fixtureOrder("QS0004", "PARTIAL", 1);
        jdbc.update("INSERT INTO trade_log(order_id,trade_price,trade_quantity) VALUES(?,1000,1)", fullyFilled);
        fixtureOrder("QS0005", "WAIT", 0);
        assertEquals(List.of("QS0001"), orders.selectPendingQuoteStockCodes());
        scheduler.start();
        worker.task.run();
        assertEquals(1, wire.provider.acquired.size());
        scheduler.stop();
        scheduler = newScheduler(orders);
        scheduler.start();
        worker.task.run();
        assertEquals(2, wire.provider.acquired.size());
        assertEquals(1, wire.provider.released.size());
    }

    @Test
    public void uncommittedCreationAndRolledBackCancellationNeverChangeOwnership() {
        scheduler.start();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            create(buyer, "BUY", 1, 1100);
            worker.task.run();
            assertTrue(wire.provider.acquired.isEmpty());
            tx.setRollbackOnly();
        });
        worker.task.run();
        assertTrue(wire.provider.acquired.isEmpty());
        Long committed = create(buyer, "BUY", 1, 1100);
        worker.task.run();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertTrue(orderService.cancelOrder(committed, buyer));
            worker.task.run();
            assertTrue(wire.provider.released.isEmpty());
            tx.setRollbackOnly();
        });
        worker.task.run();
        assertEquals("WAIT", status(committed));
        assertEquals(1, wire.provider.acquired.size());
        assertTrue(wire.provider.released.isEmpty());
    }

    @Test
    public void failedExecutionRollbackRetainsOrderAndQuoteReference() throws Exception {
        Long order = create(buyer, "BUY", 1, 1100);
        scheduler.start();
        worker.task.run();
        jdbc.update("UPDATE account SET balance=0 WHERE account_id=?", buyer);
        quote(1100, 1, 1000, 0);
        worker.task.run();
        assertEquals("WAIT", status(order));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM trade_log WHERE order_id=?", Integer.class, order));
        assertTrue(wire.provider.released.isEmpty());
        jdbc.update("UPDATE account SET balance=100000 WHERE account_id=?", buyer);
        quote(1100, 1, 1000, 0);
        worker.task.run();
        assertEquals("COMPLETED", status(order));
        assertEquals(wire.provider.acquired, wire.provider.released);
    }

    private OrderQuoteSubscriptionScheduler newScheduler(StockOrderMapper mapper) {
        return OrderQuoteSubscriptionSchedulerTest.scheduler(mapper,
                wire.context.getBean(KisSubscriptionRegistry.class), transactionManager, worker);
    }

    private Long account() {
        String name = "quotes-" + UUID.randomUUID();
        jdbc.update("INSERT INTO member(rank_id,nickname,provider) VALUES(NULL,?,'LOCAL')", name);
        Long user = jdbc.queryForObject("SELECT user_id FROM member WHERE nickname=?", Long.class, name);
        users.add(user);
        jdbc.update("INSERT INTO account(user_id,account_number,balance) VALUES(?,?,100000)", user, name);
        return jdbc.queryForObject("SELECT account_id FROM account WHERE user_id=?", Long.class, user);
    }

    private Long create(Long account, String direction, int quantity, int price) {
        assertTrue(orderService.createOrder(account, "QS0001", direction, "LIMIT", quantity, price));
        return jdbc.queryForObject("SELECT MAX(order_id) FROM stock_order WHERE account_id=?", Long.class, account);
    }

    private Long fixtureOrder(String code, String status, int quantity) {
        jdbc.update("INSERT INTO stock_order(account_id,stock_code,transaction_type,order_type,quantity,order_price,status,created_at) "
                + "VALUES(?,?,'BUY','LIMIT',?,1100,?,NOW())", buyer, code, quantity, status);
        return jdbc.queryForObject("SELECT MAX(order_id) FROM stock_order WHERE account_id=?", Long.class, buyer);
    }

    private String status(Long order) { return orders.getOrderByOrderId(order).getStatus(); }

    private static StockDTO stock(String code) {
        return StockDTO.builder().stockCode(code).stockName("합성 " + code).build();
    }

    private void quote(int askPrice, int askQty, int bidPrice, int bidQty) throws Exception {
        assertTrue("Provider must still have the quote subscription",
                wire.provider.acquired.size() > wire.provider.released.size());
        String[] fields = new String[38];
        Arrays.fill(fields, "0");
        fields[0] = "QS0001";
        fields[3] = String.valueOf(askPrice);
        fields[13] = String.valueOf(bidPrice);
        fields[23] = String.valueOf(askQty);
        fields[33] = String.valueOf(bidQty);
        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
        channel.subscribe(message -> { });
        KisWebSocketHandler handler = new KisWebSocketHandler(null, null,
                new SimpMessagingTemplate(channel), null, engine);
        handler.handleMessage(null, new TextMessage("0|H0STASP0|001|" + String.join("^", fields)));
    }
}
