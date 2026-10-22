package com.antmillion.kis;

import com.antmillion.config.GlobalExceptionHandler;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.marketdata.dto.MinuteCandle;
import com.antmillion.marketdata.dto.MinuteChartRequest;
import com.antmillion.marketdata.dto.RecentMinuteCandle;
import com.antmillion.marketdata.provider.MinuteChartProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 실제 Controller·MVC·오류 advice만 구성하고 DB·설정·외부 KIS/WS는 로드하지 않습니다. */
public class KisMinuteChartControllerTest {
    @Test
    public void dailyUrlServerDateStringsAndMissingValuesRemainCompatible() throws Exception {
        StubProvider provider = new StubProvider();
        provider.daily = Arrays.asList(new MinuteCandle("20261021", "153000", "071000", "70000", "72000", "69000"),
                new MinuteCandle(null, "152900", "", null, "0", null), null);
        LocalDate before = LocalDate.now();
        MockHttpServletResponse response = mvc(provider).perform(get("/api/kis/stream/005930"))
                .andReturn().getResponse();
        LocalDate after = LocalDate.now();
        assertEquals("005930", provider.request.getStockCode());
        assertTrue(!provider.request.getDate().isBefore(before) && !provider.request.getDate().isAfter(after));
        assertEquals(200, response.getStatus());
        JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertEquals(new ObjectMapper().readTree("[{\"stck_bsop_date\":\"20261021\",\"stck_cntg_hour\":\"153000\","
                + "\"stck_prpr\":\"071000\",\"stck_oprc\":\"70000\",\"stck_hgpr\":\"72000\",\"stck_lwpr\":\"69000\"},"
                + "{\"stck_bsop_date\":null,\"stck_cntg_hour\":\"152900\",\"stck_prpr\":\"\","
                + "\"stck_oprc\":null,\"stck_hgpr\":\"0\",\"stck_lwpr\":null},null]"), body);
        assertNull(provider.recentStockCode);
    }

    @Test
    public void recentUrlNumericPricesAndNullRowsRemainCompatible() throws Exception {
        StubProvider provider = new StubProvider();
        provider.recent = Arrays.asList(new RecentMinuteCandle("153000", 71000, 70000, 72000, 69000),
                new RecentMinuteCandle(null, 0, 0, 0, 0), null);
        MockHttpServletResponse response = mvc(provider).perform(get("/api/kis/candle/005930"))
                .andReturn().getResponse();
        assertEquals("005930", provider.recentStockCode);
        assertEquals(200, response.getStatus());
        assertEquals(new ObjectMapper().readTree("[{\"stck_cntg_hour\":\"153000\",\"stck_prpr\":71000,"
                + "\"stck_oprc\":70000,\"stck_hgpr\":72000,\"stck_lwpr\":69000},"
                + "{\"stck_cntg_hour\":null,\"stck_prpr\":0,\"stck_oprc\":0,\"stck_hgpr\":0,\"stck_lwpr\":0},null]"),
                new ObjectMapper().readTree(response.getContentAsByteArray()));
        assertNull(provider.request);
    }

    @Test
    public void emptyListsStillSerializeAsEmptyArraysForBothPaths() throws Exception {
        MockMvc mvc = mvc(new StubProvider());
        for (String path : List.of("stream", "candle")) {
            MockHttpServletResponse response = mvc.perform(get("/api/kis/" + path + "/005930"))
                    .andReturn().getResponse();
            assertEquals(200, response.getStatus());
            assertEquals("[]", response.getContentAsString());
        }
    }

    @Test
    public void nullRecentListStillProducesNoResponseBody() throws Exception {
        StubProvider provider = new StubProvider();
        provider.recent = null;
        assertNull(controller(provider).getRealtimeCandle("005930"));
        MockHttpServletResponse response = mvc(provider).perform(get("/api/kis/candle/005930"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("", response.getContentAsString());
    }

    @Test
    public void nullRowsStillSerializeAsJsonNullForBothPaths() throws Exception {
        StubProvider provider = new StubProvider();
        provider.daily = Collections.singletonList(null);
        provider.recent = Collections.singletonList(null);
        MockMvc mvc = mvc(provider);
        for (String path : List.of("stream", "candle")) {
            assertEquals("[null]", mvc.perform(get("/api/kis/" + path + "/005930"))
                    .andReturn().getResponse().getContentAsString());
        }
    }

    @Test
    public void existingRateLimitAdviceKeepsStatusBodyAndRetryAfter() throws Exception {
        StubProvider provider = new StubProvider();
        provider.failure = new RateLimitExceededException(2_000_000_000L);
        MockMvc mvc = mvc(provider);
        for (String path : List.of("stream", "candle")) {
            MockHttpServletResponse response = mvc.perform(get("/api/kis/" + path + "/005930"))
                    .andReturn().getResponse();
            assertEquals(429, response.getStatus());
            assertEquals("2", response.getHeader("Retry-After"));
            JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
            assertEquals(3, body.size());
            assertEquals("Rate limit exceeded", body.get("error").asText());
            assertEquals(2, body.get("retryAfter").asInt());
        }
    }

    @Test
    public void providerFailuresPropagateWithoutEmptyFallbackForBothPaths() {
        StubProvider provider = new StubProvider();
        provider.failure = new RuntimeException("synthetic failure");
        KisController controller = controller(provider);
        assertSame(provider.failure, assertThrows(RuntimeException.class, () -> controller.getStreamMinute("005930")));
        assertSame(provider.failure, assertThrows(RuntimeException.class, () -> controller.getRealtimeCandle("005930")));
    }

    private KisController controller(MinuteChartProvider provider) {
        return new KisController(null, null, null, provider, null, null);
    }

    private MockMvc mvc(MinuteChartProvider provider) {
        return MockMvcBuilders.standaloneSetup(controller(provider))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private static class StubProvider implements MinuteChartProvider {
        List<MinuteCandle> daily = List.of();
        List<RecentMinuteCandle> recent = List.of();
        MinuteChartRequest request;
        String recentStockCode;
        RuntimeException failure;

        @Override
        public List<MinuteCandle> getDailyMinutes(MinuteChartRequest request) {
            if (failure != null) throw failure;
            this.request = request;
            return daily;
        }

        @Override
        public List<RecentMinuteCandle> getRecentMinutes(String stockCode) {
            if (failure != null) throw failure;
            this.recentStockCode = stockCode;
            return recent;
        }
    }
}
