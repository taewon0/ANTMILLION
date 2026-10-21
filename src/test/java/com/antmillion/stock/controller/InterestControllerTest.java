package com.antmillion.stock.controller;

import com.antmillion.auth.mapper.AccountMapper;
import com.antmillion.marketdata.dto.CurrentQuote;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
import com.antmillion.stock.component.StockCache;
import com.antmillion.stock.dto.StockDTO;
import com.antmillion.stock.dto.InterestPriceResponseDTO;
import com.antmillion.stock.service.InterestService;
import com.antmillion.stock.service.StockServiceImpl;
import com.antmillion.user.dto.AccountDTO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

public class InterestControllerTest {
    private final List<String> savedCodes = List.of("GONE01", "LIVE01", "LIVE02");
    private final List<StockDTO> activeStocks = new ArrayList<>();
    private final List<List<String>> priceRequests = new ArrayList<>();
    private StockCache cache;
    private InterestController controller;

    @Before
    public void setUp() {
        activeStocks.add(new StockDTO("LIVE01", "활성하나", null));
        activeStocks.add(new StockDTO("LIVE02", "활성둘", null));
        cache = new StockCache(() -> new ArrayList<>(activeStocks));
        cache.init();
        InterestService interests = (InterestService) Proxy.newProxyInstance(InterestService.class.getClassLoader(),
                new Class<?>[]{InterestService.class}, (proxy, method, args) -> {
                    if ("getInterestStockCodes".equals(method.getName())) return savedCodes;
                    throw new AssertionError("관심종목 등록 정보를 변경하면 안 됩니다.");
                });
        AccountDTO account = new AccountDTO();
        account.setAccountId(1L);
        AccountMapper accounts = (AccountMapper) Proxy.newProxyInstance(AccountMapper.class.getClassLoader(),
                new Class<?>[]{AccountMapper.class}, (proxy, method, args) -> account);
        CurrentPriceProvider prices = new CurrentPriceProvider() {
            @Override
            public Integer getCurrentPrice(String code) {
                throw new AssertionError("관심종목은 목록 조회를 사용해야 합니다.");
            }

            @Override
            public List<CurrentQuote> getCurrentPrices(List<String> codes) {
                priceRequests.add(new ArrayList<>(codes));
                List<CurrentQuote> result = new ArrayList<>();
                // LIVE01의 가격 조회 실패와 응답 축소를 재현합니다.
                for (String code : codes) {
                    if ("LIVE01".equals(code)) continue;
                    result.add(new CurrentQuote(code, "1000", "-1.25"));
                }
                return result;
            }
        };
        controller = new InterestController(interests, prices, new StockServiceImpl(cache), accounts);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null, Collections.emptyList()));
    }

    @After
    public void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void excludesInactiveCodesBeforePagingAndMatchesPricesByCode() {
        Map<String, Object> response = controller.getInterestDetailsPaged(1, 10).getBody();
        assertEquals(2, response.get("totalItems"));
        assertEquals(List.of("LIVE01", "LIVE02"), priceRequests.get(0));
        List<?> prices = (List<?>) response.get("data");
        assertEquals(1, prices.size());
        InterestPriceResponseDTO price = (InterestPriceResponseDTO) prices.get(0);
        assertEquals("LIVE02", price.getStockCode());
        assertEquals("활성둘", price.getStockName());
        assertEquals(List.of("GONE01", "LIVE01", "LIVE02"), savedCodes);
    }

    @Test
    public void allInactiveReturnsEmptyPageWithoutPriceCalls() {
        activeStocks.clear();
        cache.refresh();
        Map<String, Object> response = controller.getInterestDetailsPaged(1, 10).getBody();
        assertEquals(0, response.get("totalItems"));
        assertEquals(0, response.get("totalPages"));
        assertTrue(((List<?>) response.get("data")).isEmpty());
        assertTrue(priceRequests.isEmpty());
    }

    @Test
    public void reappearingStockReturnsWithoutChangingSavedRegistration() {
        activeStocks.add(new StockDTO("GONE01", "재등장", null));
        cache.refresh();
        Map<String, Object> response = controller.getInterestDetailsPaged(1, 10).getBody();
        assertEquals(3, response.get("totalItems"));
        InterestPriceResponseDTO first = (InterestPriceResponseDTO) ((List<?>) response.get("data")).get(0);
        assertEquals("GONE01", first.getStockCode());
        assertEquals("재등장", first.getStockName());
    }

    @Test
    public void shrinkingActiveListClampsPreviouslyValidPage() {
        Map<String, Object> response = controller.getInterestDetailsPaged(3, 1).getBody();
        assertEquals(2, response.get("currentPage"));
        assertEquals(2, response.get("totalPages"));
        assertEquals(List.of("LIVE02"), priceRequests.get(0));
    }

    @Test
    public void existingUrlAndJsonFieldsSurviveProviderBoundary() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        org.springframework.mock.web.MockHttpServletResponse httpResponse = mvc.perform(
                get("/api/interest/details/paged").param("page", "1").param("size", "10"))
                .andReturn().getResponse();
        assertEquals(200, httpResponse.getStatus());
        String body = new String(httpResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
        JsonNode response = new ObjectMapper().readTree(body);
        assertEquals(4, response.size());
        assertEquals(1, response.get("currentPage").asInt());
        assertEquals(2, response.get("totalItems").asInt());
        assertEquals(1, response.get("totalPages").asInt());
        JsonNode price = response.get("data").get(0);
        assertEquals(4, price.size());
        assertEquals("LIVE02", price.get("stockCode").asText());
        assertEquals("활성둘", price.get("stockName").asText());
        assertEquals("1000", price.get("stck_prpr").asText());
        assertEquals("-1.25", price.get("prdy_ctrt").asText());
    }

    @Test
    public void anonymousRequestKeepsEmptyResponseWithoutPriceLookup() {
        SecurityContextHolder.clearContext();
        Map<String, Object> response = controller.getInterestDetailsPaged(1, 10).getBody();
        assertEquals(0, response.get("totalItems"));
        assertTrue(((List<?>) response.get("data")).isEmpty());
        assertTrue(priceRequests.isEmpty());
    }
}
