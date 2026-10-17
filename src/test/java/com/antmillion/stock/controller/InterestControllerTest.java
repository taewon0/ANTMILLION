package com.antmillion.stock.controller;

import com.antmillion.auth.mapper.AccountMapper;
import com.antmillion.kis.dto.CurrentPrice;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.stock.component.StockCache;
import com.antmillion.stock.dto.StockDTO;
import com.antmillion.stock.service.InterestService;
import com.antmillion.stock.service.StockServiceImpl;
import com.antmillion.user.dto.AccountDTO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

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
        KisApiService prices = new KisApiService(null, null, null, null, null, null, null, null, null, null) {
            @Override
            public List<CurrentPrice> getCurrentPricesDetail(List<String> codes) {
                priceRequests.add(new ArrayList<>(codes));
                List<CurrentPrice> result = new ArrayList<>();
                // LIVE01의 가격 조회 실패와 응답 축소를 재현합니다.
                for (String code : codes) {
                    if ("LIVE01".equals(code)) continue;
                    CurrentPrice price = new CurrentPrice();
                    price.setStockCode(code);
                    price.setCurrentPrice("1000");
                    result.add(price);
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
        CurrentPrice price = (CurrentPrice) prices.get(0);
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
        CurrentPrice first = (CurrentPrice) ((List<?>) response.get("data")).get(0);
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
}
