package com.antmillion.stock.service;

import com.antmillion.stock.dto.AssetDTO;
import com.antmillion.stock.mapper.AssetMapper;
import com.antmillion.stock.mapper.RealizedPnlMapper;
import com.antmillion.stock.mapper.StockOrderMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = {
        "classpath:test-context.xml"  // root-context.xml에서 변경
})
public class TradingEngineServiceTest {

    @Autowired
    private TradingEngineService tradingEngineService;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private StockOrderMapper stockOrderMapper;

    @Autowired
    private RealizedPnlMapper realizedPnlMapper;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    private Long testAccountId;
    private final String TEST_STOCK_CODE = "TEST01";

    @Before
    public void setUp() {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        KeyHolder memberKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO member (rank_id, nickname, provider) VALUES (NULL, ?, 'LOCAL')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "fixture-" + UUID.randomUUID());
            return statement;
        }, memberKey);
        Long userId = memberKey.getKey().longValue();
        KeyHolder accountKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO account (user_id, account_number, balance) VALUES (?, ?, 50000000)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, "fixture-" + UUID.randomUUID());
            return statement;
        }, accountKey);
        testAccountId = accountKey.getKey().longValue();
        jdbcTemplate.update("INSERT INTO stock (stock_code, stock_name) VALUES (?, ?)",
                TEST_STOCK_CODE, "테스트종목");
    }

    @AfterTransaction
    public void verifyFixtureRollback() {
        assertEquals("계좌 fixture 롤백", Integer.valueOf(0), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account WHERE account_id = ?", Integer.class, testAccountId));
        assertEquals("종목 fixture 롤백", Integer.valueOf(0), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock WHERE stock_code = ?", Integer.class, TEST_STOCK_CODE));
    }

    /**
     * processExecution 동작 테스트
     */
    @Test
    @Transactional
    public void testProcessExecution() {
        System.out.println("\n========== processExecution 테스트 ==========");

        // 1. 매수 주문 생성 및 체결
        System.out.println("--- 매수 체결 ---");
        insertBuyOrder(10, 10000);

        Long buyOrderId = jdbcTemplate.queryForObject(
                "SELECT order_id FROM stock_order WHERE transaction_type = 'BUY' ORDER BY order_id DESC LIMIT 1",
                Long.class
        );
        System.out.println("매수 주문 ID: " + buyOrderId);

        tradingEngineService.processExecution(TEST_STOCK_CODE, "BUY", 10000, 10);

        // 매수 체결 후 확인
        int tradeLogCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trade_log WHERE order_id = ?",
                Integer.class,
                buyOrderId
        );
        System.out.println("매수 체결 로그 수: " + tradeLogCount);

        Integer assetQty = jdbcTemplate.queryForObject(
                "SELECT quantity FROM asset WHERE account_id = ? AND stock_code = ?",
                Integer.class,
                testAccountId, TEST_STOCK_CODE
        );
        System.out.println("매수 후 보유 수량: " + assetQty);

        // 2. 매도 주문 생성 및 체결
        System.out.println("\n--- 매도 체결 ---");
        insertSellOrder(5, 12000);

        Long sellOrderId = jdbcTemplate.queryForObject(
                "SELECT order_id FROM stock_order WHERE transaction_type = 'SELL' ORDER BY order_id DESC LIMIT 1",
                Long.class
        );
        System.out.println("매도 주문 ID: " + sellOrderId);

        tradingEngineService.processExecution(TEST_STOCK_CODE, "SELL", 12000, 5);

        // 매도 체결 후 확인
        int sellTradeLogCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trade_log WHERE order_id = ?",
                Integer.class,
                sellOrderId
        );
        System.out.println("매도 체결 로그 수: " + sellTradeLogCount);

        Integer afterSellQty = jdbcTemplate.queryForObject(
                "SELECT quantity FROM asset WHERE account_id = ? AND stock_code = ?",
                Integer.class,
                testAccountId, TEST_STOCK_CODE
        );
        System.out.println("매도 후 보유 수량: " + afterSellQty);

        // 3. realized_pnl 확인
        int pnlCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM realized_pnl WHERE account_id = ?",
                Integer.class,
                testAccountId
        );
        System.out.println("realized_pnl 레코드 수: " + pnlCount);

        if (pnlCount > 0) {
            List<Map<String, Object>> pnlList = jdbcTemplate.queryForList(
                    "SELECT * FROM realized_pnl WHERE account_id = ?",
                    testAccountId
            );
            System.out.println("실현손익 내역: " + pnlList);
        } else {
            System.out.println("realized_pnl에 데이터가 없습니다!");
        }

        // 4. trade_log 전체 확인
        List<Map<String, Object>> allTrades = jdbcTemplate.queryForList(
                "SELECT * FROM trade_log"
        );
        System.out.println("전체 체결 로그: " + allTrades);

        System.out.println("\n processExecution 테스트 완료");
    }

    /**
     * 테스트 1: 10주 각 10000원에 매수 후 5주 각 12000원에 매도
     *
     * 매수: 10주 * 10,000원 = 100,000원
     * 매도: 5주 * 12,000원 = 60,000원
     * 차감원가: floor(100,000 * 5 / 10) = 50,000원
     * 실현손익: 60,000 - 50,000 = 10,000원
     * 수익률: 10,000 / 50,000 = 0.2 (20%)
     *
     * 남은 자산: 5주, 매수금액 50,000원, 평균단가 10,000원
     */
    @Test
    @Transactional
    public void testCase1_PartialSellWithProfit() {
        System.out.println("\n========== 테스트 1: 부분 매도 (이익) ==========");

        // 1. 매수: 10주 * 10,000원
        insertBuyOrder(10, 10000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "BUY", 10000, 10);

        // 2. 매도: 5주 * 12,000원
        insertSellOrder(5, 12000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "SELL", 12000, 5);

        // 3. 실현손익 검증
        Map<String, Object> pnl = jdbcTemplate.queryForMap(
                "SELECT * FROM realized_pnl WHERE account_id = ?", testAccountId
        );

        assertEquals("매도 수량", 5, ((Number)pnl.get("sell_quantity")).intValue());
        assertEquals("매도 금액", 60000L, ((Number)pnl.get("sell_amount")).longValue());
        assertEquals("차감 원가", 50000L, ((Number)pnl.get("cost_basis")).longValue());
        assertEquals("실현 손익", 10000L, ((Number)pnl.get("realized_profit")).longValue());
        assertEquals("평균 단가", 10000.0, ((Number)pnl.get("avg_price")).doubleValue(), 0.01);

        // 4. 자산 검증
        AssetDTO asset = assetMapper.getAssetForSell(testAccountId, TEST_STOCK_CODE);
        assertEquals("남은 수량", 5, asset.getQuantity().intValue());
        assertEquals("남은 매수금액", 50000L, asset.getPurchaseAmount().longValue());
        assertEquals("평균단가 유지", 10000.0, asset.getAvgPrice(), 0.01);

        System.out.println("테스트 1 통과");
    }

    /**
     * 테스트 2: 3주 각 10000원에 매수 후 2주 각 10001원에 매수 후 2주 각 11000원에 매도
     *
     * 1차 매수: 3주 * 10,000원 = 30,000원
     * 2차 매수: 2주 * 10,001원 = 20,002원
     * 총 보유: 5주, 총 매수금액 50,002원, 평균단가 10,000.4원
     *
     * 매도: 2주 * 11,000원 = 22,000원
     * 차감원가: floor(50,002 * 2 / 5) = floor(20,000.8) = 20,000원
     * 실현손익: 22,000 - 20,000 = 2,000원
     * 수익률: 2,000 / 20,000 = 0.1 (10%)
     *
     * 남은 자산: 3주, 매수금액 30,002원
     */
    @Test
    @Transactional
    public void testCase2_MultipleBuysAndPartialSell() {
        System.out.println("\n========== 테스트 2: 여러 번 매수 후 부분 매도 ==========");

        // 1. 1차 매수: 3주 * 10,000원
        insertBuyOrder(3, 10000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "BUY", 10000, 3);

        // 2. 2차 매수: 2주 * 10,001원
        insertBuyOrder(2, 10001);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "BUY", 10001, 2);

        // 3. 매도: 2주 * 11,000원
        insertSellOrder(2, 11000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "SELL", 11000, 2);

        // 4. 실현손익 검증
        Map<String, Object> pnl = jdbcTemplate.queryForMap(
                "SELECT * FROM realized_pnl WHERE account_id = ?", testAccountId
        );

        assertEquals("매도 수량", 2, ((Number)pnl.get("sell_quantity")).intValue());
        assertEquals("매도 금액", 22000L, ((Number)pnl.get("sell_amount")).longValue());
        assertEquals("차감 원가", 20000L, ((Number)pnl.get("cost_basis")).longValue());
        assertEquals("실현 손익", 2000L, ((Number)pnl.get("realized_profit")).longValue());

        // 5. 자산 검증
        AssetDTO asset = assetMapper.getAssetForSell(testAccountId, TEST_STOCK_CODE);
        assertEquals("남은 수량", 3, asset.getQuantity().intValue());
        assertEquals("남은 매수금액", 30002L, asset.getPurchaseAmount().longValue());

        System.out.println("테스트 2 통과");
    }

    /**
     * 테스트 3: 3주 각 10000원에 매수 후 2주 각 10001원에 매수 후 2주 각 11000원에 매도 후 3주 각 9000원에 매도
     *
     * 1차 매수: 3주 * 10,000원 = 30,000원
     * 2차 매수: 2주 * 10,001원 = 20,002원
     * 총 보유: 5주, 총 매수금액 50,002원, 평균단가 10,000.4원
     *
     * 1차 매도: 2주 * 11,000원 = 22,000원
     *   차감원가: floor(50,002 * 2 / 5) = floor(20,000.8) = 20,000원
     *   실현손익: 22,000 - 20,000 = 2,000원
     *   남은 자산: 3주, 매수금액 30,002원
     *
     * 2차 매도: 3주 * 9,000원 = 27,000원 (전량매도)
     *   차감원가: 30,002원 (남은 매수금액 전부)
     *   실현손익: 27,000 - 30,002 = -3,002원 (손실)
     *
     * 남은 자산: 0주 (자산 삭제됨)
     */
    @Test
    @Transactional
    public void testCase3_PartialSellProfitThenFullSellLoss() {
        System.out.println("\n========== 테스트 3: 부분 매도 (이익) 후 전량 매도 (손실) ==========");

        // 1. 1차 매수: 3주 * 10,000원
        insertBuyOrder(3, 10000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "BUY", 10000, 3);

        // 2. 2차 매수: 2주 * 10,001원
        insertBuyOrder(2, 10001);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "BUY", 10001, 2);

        // 3. 1차 매도: 2주 * 11,000원
        insertSellOrder(2, 11000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "SELL", 11000, 2);

        // 4. 2차 매도: 3주 * 9,000원 (전량매도)
        insertSellOrder(3, 9000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "SELL", 9000, 3);

        // 5. 실현손익 검증 (2건)
        List<Map<String, Object>> pnlList = jdbcTemplate.queryForList(
                "SELECT * FROM realized_pnl WHERE account_id = ? ORDER BY sell_date, real_id",
                testAccountId
        );

        assertEquals("실현손익 건수", 2, pnlList.size());

        // 1차 매도 검증 (이익)
        Map<String, Object> pnl1 = pnlList.get(0);
        assertEquals("1차 매도 수량", 2, ((Number)pnl1.get("sell_quantity")).intValue());
        assertEquals("1차 매도 금액", 22000L, ((Number)pnl1.get("sell_amount")).longValue());
        assertEquals("1차 차감 원가", 20000L, ((Number)pnl1.get("cost_basis")).longValue());
        assertEquals("1차 실현 손익", 2000L, ((Number)pnl1.get("realized_profit")).longValue());

        // 2차 매도 검증 (손실, 전량매도)
        Map<String, Object> pnl2 = pnlList.get(1);
        assertEquals("2차 매도 수량", 3, ((Number)pnl2.get("sell_quantity")).intValue());
        assertEquals("2차 매도 금액", 27000L, ((Number)pnl2.get("sell_amount")).longValue());
        assertEquals("2차 차감 원가", 30002L, ((Number)pnl2.get("cost_basis")).longValue());
        assertEquals("2차 실현 손익", -3002L, ((Number)pnl2.get("realized_profit")).longValue());

        // 6. 자산 삭제 검증 (전량 매도)
        AssetDTO asset = assetMapper.getAssetForSell(testAccountId, TEST_STOCK_CODE);
        assertNull("전량 매도 후 자산 삭제", asset);

        System.out.println("테스트 3 통과");
    }

    /**
     * 테스트 4: 3주 각 10000원에 매수 후 1주씩 3번 나눠서 매도
     *
     * 매수: 3주 * 10,000원 = 30,000원
     *
     * 1차 매도: 1주 * 11,000원
     *   차감원가: floor(30,000 * 1 / 3) = 10,000원
     *   실현손익: 11,000 - 10,000 = 1,000원
     *
     * 2차 매도: 1주 * 11,000원
     *   차감원가: floor(20,000 * 1 / 2) = 10,000원
     *   실현손익: 11,000 - 10,000 = 1,000원
     *
     * 3차 매도: 1주 * 9,000원
     *   차감원가: 10,000원 (마지막 남은 금액)
     *   실현손익: 9,000 - 10,000 = -1,000원
     */
    @Test
    @Transactional
    public void testCase4_MultipleSells() {
        System.out.println("\n========== 테스트 4: 여러 번 나눠서 매도 ==========");

        // 1. 매수: 3주 * 10,000원
        insertBuyOrder(3, 10000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "BUY", 10000, 3);

        // 2. 1차 매도: 1주 * 11,000원
        insertSellOrder(1, 11000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "SELL", 11000, 1);

        // 3. 2차 매도: 1주 * 11,000원
        insertSellOrder(1, 11000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "SELL", 11000, 1);

        // 4. 3차 매도: 1주 * 9,000원
        insertSellOrder(1, 9000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "SELL", 9000, 1);

        // 5. 실현손익 검증 (3건)
        List<Map<String, Object>> pnlList = jdbcTemplate.queryForList(
                "SELECT * FROM realized_pnl WHERE account_id = ? ORDER BY sell_date, real_id",
                testAccountId
        );

        assertEquals("실현손익 건수", 3, pnlList.size());

        // 1차 매도 검증
        Map<String, Object> pnl1 = pnlList.get(0);
        assertEquals("1차 실현손익", 1000L, ((Number)pnl1.get("realized_profit")).longValue());

        // 2차 매도 검증
        Map<String, Object> pnl2 = pnlList.get(1);
        assertEquals("2차 실현손익", 1000L, ((Number)pnl2.get("realized_profit")).longValue());

        // 3차 매도 검증
        Map<String, Object> pnl3 = pnlList.get(2);
        assertEquals("3차 실현손익", -1000L, ((Number)pnl3.get("realized_profit")).longValue());

        // 6. 자산 삭제 검증
        AssetDTO asset = assetMapper.getAssetForSell(testAccountId, TEST_STOCK_CODE);
        assertNull("전량 매도 후 자산 삭제", asset);

        System.out.println("테스트 4 통과");
    }

    /**
     * 테스트 5: 10주 각 10000원에 매수 후 5주씩 2번 나눠서 매도 (손익분기)
     *
     * 매수: 10주 * 10,000원 = 100,000원
     *
     * 1차 매도: 5주 * 10,000원
     *   차감원가: floor(100,000 * 5 / 10) = 50,000원
     *   실현손익: 50,000 - 50,000 = 0원
     *
     * 2차 매도: 5주 * 10,500원
     *   차감원가: 50,000원 (남은 금액)
     *   실현손익: 52,500 - 50,000 = 2,500원
     */
    @Test
    @Transactional
    public void testCase5_BreakEvenAndProfit() {
        System.out.println("\n========== 테스트 5: 손익분기 + 이익 ==========");

        // 1. 매수: 10주 * 10,000원
        insertBuyOrder(10, 10000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "BUY", 10000, 10);

        // 2. 1차 매도: 5주 * 10,000원 (손익분기)
        insertSellOrder(5, 10000);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "SELL", 10000, 5);

        // 3. 2차 매도: 5주 * 10,500원 (이익)
        insertSellOrder(5, 10500);
        tradingEngineService.processExecution(TEST_STOCK_CODE, "SELL", 10500, 5);

        // 4. 실현손익 검증 (2건)
        List<Map<String, Object>> pnlList = jdbcTemplate.queryForList(
                "SELECT * FROM realized_pnl WHERE account_id = ? ORDER BY sell_date, real_id",
                testAccountId
        );

        assertEquals("실현손익 건수", 2, pnlList.size());

        // 1차 매도 검증 (손익분기)
        Map<String, Object> pnl1 = pnlList.get(0);
        assertEquals("1차 실현손익", 0L, ((Number)pnl1.get("realized_profit")).longValue());

        // 2차 매도 검증 (이익)
        Map<String, Object> pnl2 = pnlList.get(1);
        assertEquals("2차 실현손익", 2500L, ((Number)pnl2.get("realized_profit")).longValue());

        // 5. 자산 삭제 검증
        AssetDTO asset = assetMapper.getAssetForSell(testAccountId, TEST_STOCK_CODE);
        assertNull("전량 매도 후 자산 삭제", asset);

        System.out.println("테스트 5 통과");
    }

    // ===== 헬퍼 메소드 =====

    private void insertBuyOrder(int quantity, int price) {
        jdbcTemplate.update(
                "INSERT INTO stock_order (account_id, stock_code, transaction_type, order_type, " +
                        "quantity, order_price, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'BUY', 'LIMIT', ?, ?, 'WAIT', NOW(), NOW())",  // PENDING → WAIT
                testAccountId, TEST_STOCK_CODE, quantity, price
        );
    }

    private void insertSellOrder(int quantity, int price) {
        jdbcTemplate.update(
                "INSERT INTO stock_order (account_id, stock_code, transaction_type, order_type, " +
                        "quantity, order_price, status, created_at, updated_at) " +
                        "VALUES (?, ?, 'SELL', 'LIMIT', ?, ?, 'WAIT', NOW(), NOW())",  // PENDING → WAIT
                testAccountId, TEST_STOCK_CODE, quantity, price
        );
    }
}
