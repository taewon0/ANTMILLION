package com.antmillion.kis;

import com.antmillion.config.GlobalExceptionHandler;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.dto.StockVolumeRank;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.marketdata.dto.RankedStock;
import com.antmillion.marketdata.provider.StockRankingProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 실제 Controller·기존 오류 advice에 Provider 대역만 연결하며 DB·외부 설정은 로드하지 않습니다. */
public class KisStockRankingControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void listKeepsExactLegacyJsonNamesStringsOrderAndNullableFavorite() throws Exception {
        StubProvider provider = new StubProvider();
        provider.ranks = Arrays.asList(
                new RankedStock("합성종목", "000002", "02", "071000", "001234", "-1.20", false),
                new RankedStock(null, "000001", "01", "", null, null, true),
                new RankedStock(null, null, null, null, null, null, null), null);
        JsonNode body = json(mvc(provider).perform(get("/api/kis/volumeRank")).andReturn().getResponse());
        assertEquals(4, body.size());
        for (int i = 0; i < 3; i++) assertLegacyJson(provider.ranks.get(i), body.get(i));
        assertTrue(body.get(3).isNull());
        assertEquals(1, provider.tradingCalls);
        assertEquals(0, provider.volumeCalls);
    }

    @Test
    public void defaultPageAndPartialLastPageKeepEnvelopeAndOrder() throws Exception {
        StubProvider provider = new StubProvider();
        MockMvc mvc = mvc(provider);
        JsonNode defaults = json(mvc.perform(get("/api/kis/volumeRank/paged")).andReturn().getResponse());
        assertEquals(4, defaults.size());
        assertEquals(1, defaults.get("currentPage").asInt());
        assertEquals(3, defaults.get("totalItems").asInt());
        assertEquals(1, defaults.get("totalPages").asInt());
        assertEquals(3, defaults.get("data").size());
        JsonNode last = json(mvc.perform(get("/api/kis/volumeRank/paged").param("page", "2").param("size", "2"))
                .andReturn().getResponse());
        assertEquals(2, last.get("currentPage").asInt());
        assertEquals(3, last.get("totalItems").asInt());
        assertEquals(2, last.get("totalPages").asInt());
        assertEquals(1, last.get("data").size());
        assertLegacyJson(provider.ranks.get(2), last.get("data").get(0));
        assertEquals(2, provider.tradingCalls);
        assertEquals(0, provider.volumeCalls);
    }

    @Test
    public void codeLookupKeepsVolumeQuerySourceOrderAndMembershipFiltering() throws Exception {
        StubProvider provider = new StubProvider();
        JsonNode body = json(mvc(provider).perform(post("/api/kis/stocksByCode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stockCodes\":[\"000003\",\"missing\",\"000001\",\"000001\"]}"))
                .andReturn().getResponse());
        assertEquals(2, body.size());
        assertLegacyJson(provider.ranks.get(0), body.get(0));
        assertLegacyJson(provider.ranks.get(2), body.get(1));
        assertEquals(0, provider.tradingCalls);
        assertEquals(1, provider.volumeCalls);
    }

    @Test
    public void missingNullAndEmptyCodeListsSkipProvider() throws Exception {
        StubProvider provider = new StubProvider();
        MockMvc mvc = mvc(provider);
        for (String request : List.of("{}", "{\"stockCodes\":null}", "{\"stockCodes\":[]}")) {
            assertEquals("[]", mvc.perform(post("/api/kis/stocksByCode").contentType(MediaType.APPLICATION_JSON)
                    .content(request)).andReturn().getResponse().getContentAsString());
        }
        assertEquals(0, provider.tradingCalls + provider.volumeCalls);
    }

    @Test
    public void emptyResultsKeepListAndPagingResponses() throws Exception {
        StubProvider provider = new StubProvider();
        provider.ranks = List.of();
        MockMvc mvc = mvc(provider);
        assertEquals("[]", mvc.perform(get("/api/kis/volumeRank")).andReturn().getResponse().getContentAsString());
        JsonNode page = json(mvc.perform(get("/api/kis/volumeRank/paged")).andReturn().getResponse());
        assertEquals(0, page.get("data").size());
        assertEquals(0, page.get("totalItems").asInt());
        assertEquals(0, page.get("totalPages").asInt());
        assertEquals("[]", mvc.perform(post("/api/kis/stocksByCode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stockCodes\":[\"000001\"]}")).andReturn().getResponse().getContentAsString());
    }

    @Test
    public void pagePreservesNullRowsAndExistingOutOfRangeFailure() throws Exception {
        StubProvider provider = new StubProvider();
        provider.ranks = Arrays.asList((RankedStock) null);
        JsonNode page = json(mvc(provider).perform(get("/api/kis/volumeRank/paged")).andReturn().getResponse());
        assertTrue(page.get("data").get(0).isNull());
        assertThrows(IndexOutOfBoundsException.class, () -> controller(provider).kisStockVolumeRankPaged(0, 10));
        assertThrows(IllegalArgumentException.class, () -> controller(provider).kisStockVolumeRankPaged(2, 10));
        // 코드 필터는 기존과 같이 null 행을 역참조하며 새 fallback을 추가하지 않습니다.
        assertThrows(NullPointerException.class, () -> controller(provider).getStocksByCode(
                Map.of("stockCodes", List.of("000001"))));
    }

    @Test
    public void allThreeRoutesKeepExisting429AdviceAndRetryAfter() throws Exception {
        StubProvider provider = new StubProvider();
        provider.failure = new RateLimitExceededException(2_000_000_000L);
        MockMvc mvc = mvc(provider);
        for (MockHttpServletResponse response : List.of(
                mvc.perform(get("/api/kis/volumeRank")).andReturn().getResponse(),
                mvc.perform(get("/api/kis/volumeRank/paged")).andReturn().getResponse(),
                mvc.perform(post("/api/kis/stocksByCode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockCodes\":[\"000001\"]}")).andReturn().getResponse())) {
            assertEquals(429, response.getStatus());
            assertEquals("2", response.getHeader("Retry-After"));
            JsonNode body = mapper.readTree(response.getContentAsByteArray());
            assertEquals(3, body.size());
            assertEquals("Rate limit exceeded", body.get("error").asText());
            assertEquals(2, body.get("retryAfter").asInt());
        }
    }

    @Test
    public void failuresPropagateFromAllThreePathsWithoutEmptyFallback() {
        StubProvider provider = new StubProvider();
        provider.failure = new RuntimeException("synthetic failure");
        KisController controller = controller(provider);
        assertSame(provider.failure, assertThrows(RuntimeException.class, controller::kisStockVolumeRank));
        assertSame(provider.failure, assertThrows(RuntimeException.class, () -> controller.kisStockVolumeRankPaged(1, 10)));
        assertSame(provider.failure, assertThrows(RuntimeException.class, () -> controller.getStocksByCode(
                Map.of("stockCodes", List.of("000001")))));
    }

    private void assertLegacyJson(RankedStock stock, JsonNode actual) {
        StockVolumeRank legacy = new StockVolumeRank();
        legacy.setStockName(stock.getStockName());
        legacy.setStockCode(stock.getStockCode());
        legacy.setRank(stock.getRank());
        legacy.setPresentPrice(stock.getCurrentPrice());
        legacy.setAcmVolume(stock.getAccumulatedVolume());
        legacy.setPrdyCtrt(stock.getChangeRate());
        legacy.setIsFavorite(stock.getFavorite());
        assertEquals(mapper.valueToTree(legacy), actual);
        assertEquals(7, actual.size());
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus());
        return mapper.readTree(response.getContentAsByteArray());
    }

    private KisController controller(StockRankingProvider provider) {
        return new KisController(null, null, null, null, null, provider);
    }

    private MockMvc mvc(StockRankingProvider provider) {
        return MockMvcBuilders.standaloneSetup(controller(provider))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private static class StubProvider implements StockRankingProvider {
        List<RankedStock> ranks = List.of(
                new RankedStock("합성하나", "000001", "1", "071000", "100", "1.25", false),
                new RankedStock("합성둘", "000002", "2", "2000", "200", "-0.50", true),
                new RankedStock("합성셋", "000003", "3", "3000", "300", "0", false));
        int tradingCalls;
        int volumeCalls;
        RuntimeException failure;

        @Override public List<RankedStock> getTradingValueRanks() {
            tradingCalls++;
            if (failure != null) throw failure;
            return ranks;
        }

        @Override public List<RankedStock> getVolumeRanks() {
            volumeCalls++;
            if (failure != null) throw failure;
            return ranks;
        }
    }
}
