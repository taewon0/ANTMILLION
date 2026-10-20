package com.antmillion.stock.controller;

import com.antmillion.auth.mapper.AccountMapper;
import com.antmillion.stock.dto.StockOrderDTO;
import com.antmillion.stock.mapper.StockOrderMapper;
import com.antmillion.stock.service.StockOrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration("classpath:order-modify-context.xml")
@Transactional
public class StockOrderModifyTest {
    @Autowired private StockOrderService stockOrderService;
    @Autowired private StockOrderMapper stockOrderMapper;
    @Autowired private AccountMapper accountMapper;
    @Autowired private DataSource dataSource;

    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private Long ownerUserId;
    private Long ownerAccountId;
    private Long otherUserId;
    private Long otherAccountId;
    private Long orderId;
    private Map<String, Object> originalOrder;
    private List<Map<String, Object>> originalAccounts;

    @Before
    public void setUp() {
        SecurityContextHolder.clearContext();
        jdbc = new JdbcTemplate(dataSource);
        ownerUserId = insertMember();
        ownerAccountId = insertAccount(ownerUserId);
        otherUserId = insertMember();
        otherAccountId = insertAccount(otherUserId);
        jdbc.update("INSERT INTO stock (stock_code, stock_name) VALUES ('MODIFY01', '정정 테스트')");
        StockOrderDTO order = new StockOrderDTO();
        order.setAccountId(ownerAccountId);
        order.setStockCode("MODIFY01");
        order.setTransactionType("BUY");
        order.setOrderType("LIMIT");
        order.setQuantity(10);
        order.setOrderPrice(10000);
        order.setStatus("WAIT");
        stockOrderMapper.insertOrder(order);
        orderId = order.getOrderId();
        originalOrder = readOrder();
        originalAccounts = jdbc.queryForList("SELECT * FROM account ORDER BY account_id");
        // 애플리케이션 컨텍스트와 필터는 사용하지 않고 인증 정보를 명시적인 테스트 대역으로 제공합니다.
        mvc = MockMvcBuilders.standaloneSetup(
                new StockOrderController(stockOrderService, accountMapper)).build();
        login(ownerUserId);
    }

    @After
    public void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void ownerCanModifyOnlyPriceAndQuantityDespiteForgedFields() throws Exception {
        Map<String, Object> request = validRequest();
        request.put("accountId", otherAccountId);
        request.put("status", "COMPLETED");
        request.put("stockCode", "FORGED");
        request.put("transactionType", "SELL");
        request.put("orderType", "MARKET");
        request.put("createdAt", "2000-01-01T00:00:00");
        MvcResult result = submit(request, 200, true);
        assertEquals("정정이 완료되었습니다.", body(result).get("message").asText());
        Map<String, Object> updated = readOrder();
        assertEquals(12, ((Number) updated.get("quantity")).intValue());
        assertEquals(11000L, ((Number) updated.get("order_price")).longValue());
        for (String column : new String[]{"account_id", "stock_code", "transaction_type", "order_type", "status", "created_at"}) {
            assertEquals(column, originalOrder.get(column), updated.get(column));
        }
        assertNoFinancialChanges();
    }

    @Test
    public void otherUserCannotModifyEvenWithOwnerAccountInBody() throws Exception {
        login(otherUserId);
        Map<String, Object> request = validRequest();
        request.put("accountId", ownerAccountId);
        submit(request, 403, false);
        assertUnchanged();
    }

    @Test
    public void unauthenticatedRequestsAreRejected() throws Exception {
        SecurityContextHolder.clearContext();
        submit(validRequest(), 401, false);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "test", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        submit(validRequest(), 401, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ownerUserId, null));
        submit(validRequest(), 401, false);
        login("invalid-principal");
        submit(validRequest(), 401, false);
        assertUnchanged();
    }

    @Test
    public void userWithoutAccountCannotUseBodyAccount() throws Exception {
        login(insertMember());
        Map<String, Object> request = validRequest();
        request.put("accountId", ownerAccountId);
        submit(request, 403, false);
        assertUnchanged();
    }

    @Test
    public void invalidOrderIdsAreRejected() throws Exception {
        assertInvalidField("orderId", new Object[]{null, 0, -1, new BigDecimal(orderId + ".5"),
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)});
    }

    @Test
    public void invalidQuantitiesAreRejected() throws Exception {
        assertInvalidField("quantity", new Object[]{null, 0, -1, new BigDecimal("12.5"), 2147483648L});
    }

    @Test
    public void invalidPricesAreRejected() throws Exception {
        assertInvalidField("orderPrice", new Object[]{null, 0, -1, new BigDecimal("11000.5"), 2147483648L});
    }

    @Test
    public void missingOrderIsRejected() throws Exception {
        Map<String, Object> request = validRequest();
        request.put("orderId", Long.MAX_VALUE);
        submit(request, 200, false);
        assertUnchanged();
    }

    @Test
    public void nonWaitingStatesCannotBeModified() throws Exception {
        for (String state : new String[]{"PARTIAL", "COMPLETED", "CANCEL", "UNKNOWN"}) {
            jdbc.update("UPDATE stock_order SET status = ? WHERE order_id = ?", state, orderId);
            originalOrder = readOrder();
            submit(validRequest(), 200, false);
            assertUnchanged();
        }
    }

    @Test
    public void quantityCannotBeReducedBelowExecutedQuantity() throws Exception {
        jdbc.update("INSERT INTO trade_log (order_id, trade_price, trade_quantity) VALUES (?, 10000, 5)", orderId);
        Map<String, Object> request = validRequest();
        request.put("quantity", 4);
        submit(request, 200, false);
        assertEquals(originalOrder, readOrder());
        assertEquals(Integer.valueOf(5), jdbc.queryForObject(
                "SELECT SUM(trade_quantity) FROM trade_log WHERE order_id = ?", Integer.class, orderId));
        assertEquals(originalAccounts, jdbc.queryForList("SELECT * FROM account ORDER BY account_id"));
    }

    @Test
    public void malformedBodiesDoNotChangeOrder() throws Exception {
        String[] bodies = {"null", "{}", "{", "{\"quantity\":\"invalid\"}", "[]"};
        for (String request : bodies) {
            MvcResult result = mvc.perform(post("/api/stock/modify")
                    .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn();
            // 빈 객체는 서비스 검증에 전달하고 읽을 수 없는 JSON은 스프링 MVC에서 거부합니다.
            if ("{}".equals(request)) {
                assertEquals(200, result.getResponse().getStatus());
                assertFalse(body(result).get("success").asBoolean());
            } else {
                assertEquals(400, result.getResponse().getStatus());
            }
            assertUnchanged();
        }
    }

    @Test
    public void serviceRequiresTrustedAccountEvenWhenBodyClaimsOwner() {
        StockOrderDTO request = dto();
        request.setAccountId(ownerAccountId);
        assertThrows(AccessDeniedException.class, () -> stockOrderService.modifyOrder(null, request));
        assertThrows(AccessDeniedException.class, () -> stockOrderService.modifyOrder(otherAccountId, request));
        assertThrows(IllegalArgumentException.class, () -> stockOrderService.modifyOrder(ownerAccountId, null));
        assertUnchanged();
    }

    @Test
    public void updateSqlRetainsAccountAndWaitingStateGuards() {
        StockOrderDTO request = dto();
        request.setAccountId(otherAccountId);
        assertEquals(0, stockOrderMapper.updateOrder(request));
        assertUnchanged();
        request.setAccountId(ownerAccountId);
        jdbc.update("UPDATE stock_order SET status = 'CANCEL' WHERE order_id = ?", orderId);
        originalOrder = readOrder();
        assertEquals(0, stockOrderMapper.updateOrder(request));
        assertUnchanged();
    }

    private void assertInvalidField(String field, Object[] values) throws Exception {
        Map<String, Object> missing = validRequest();
        missing.remove(field);
        submit(missing, 200, false);
        assertUnchanged();
        for (Object value : values) {
            Map<String, Object> request = validRequest();
            request.put(field, value);
            submit(request, 200, false);
            assertUnchanged();
        }
    }

    private MvcResult submit(Map<String, Object> request, int status, boolean success) throws Exception {
        MvcResult result = mvc.perform(post("/api/stock/modify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(request))).andReturn();
        assertEquals(result.getResponse().getContentAsString(), status, result.getResponse().getStatus());
        JsonNode response = body(result);
        assertEquals(success, response.get("success").asBoolean());
        assertTrue(response.hasNonNull("message"));
        assertEquals(2, response.size());
        return result;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private Map<String, Object> validRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orderId", orderId);
        request.put("quantity", 12);
        request.put("orderPrice", 11000);
        return request;
    }

    private StockOrderDTO dto() {
        StockOrderDTO request = new StockOrderDTO();
        request.setOrderId(orderId);
        request.setQuantity(12);
        request.setOrderPrice(11000);
        return request;
    }

    private void login(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, Collections.emptyList()));
    }

    private Map<String, Object> readOrder() {
        return jdbc.queryForMap("SELECT * FROM stock_order WHERE order_id = ?", orderId);
    }

    private void assertUnchanged() {
        assertEquals(originalOrder, readOrder());
        assertNoFinancialChanges();
    }

    private void assertNoFinancialChanges() {
        assertEquals(originalAccounts, jdbc.queryForList("SELECT * FROM account ORDER BY account_id"));
        for (String table : new String[]{"asset", "trade_log", "realized_pnl"}) {
            assertEquals(table, Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
    }

    private Long insertMember() {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO member (rank_id, nickname, provider) VALUES (NULL, ?, 'LOCAL')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "modify-" + UUID.randomUUID());
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private Long insertAccount(Long userId) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO account (user_id, account_number, balance) VALUES (?, ?, 50000000)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, "modify-" + UUID.randomUUID());
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
