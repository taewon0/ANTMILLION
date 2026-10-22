package com.antmillion.kis;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.dto.ChartStockPrice;
import com.antmillion.kis.dto.ChartStockPriceRequest;
import com.antmillion.kis.dto.CurrentPriceRequest;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisChartRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisPeriodChartProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.marketdata.dto.PeriodCandle;
import com.antmillion.marketdata.dto.PeriodChartRequest;
import com.antmillion.marketdata.provider.PeriodChartProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 KIS 서비스·캐시·limiter를 HTTP·메모리 Redis 대역에만 연결합니다. */
public class KisPeriodChartProviderTest {
    private static final String CHART = "{\"output2\":["
            + "{\"stck_bsop_date\":\"20261021\",\"stck_clpr\":\"071000\",\"stck_oprc\":\"70000\","
            + "\"stck_hgpr\":\"72000\",\"stck_lwpr\":\"69000\"},"
            + "{\"stck_bsop_date\":\"20261020\",\"stck_clpr\":\"\",\"stck_oprc\":null,"
            + "\"stck_hgpr\":\"0\"}]}";
    private MemoryRedis redis;
    private MockRestServiceServer server;
    private KisApiService legacy;
    private PeriodChartProvider provider;
    private RateLimitedKisApiClient limiter;
    private KisChartRedisRepository charts;
    private String baseUrl;

    @Before
    public void setUp() {
        baseUrl = "https://kis.test";
        redis = new MemoryRedis();
        redis.values.put("kis:access_token", "synthetic-token");
        RestTemplate rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        KisConfig config = new KisConfig() {
            @Override public String getBaseUrl() { return baseUrl; }
        };
        config.appKey = "synthetic-key";
        config.appSecret = "synthetic-secret";
        limiter = new RateLimitedKisApiClient(redis);
        charts = new KisChartRedisRepository(redis, new ObjectMapper());
        legacy = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis),
                charts, null, null, null, null, limiter);
        provider = new KisPeriodChartProvider(legacy);
    }

    @Test
    public void allPeriodsKeepKrxAdjustedRequestHeadersStringsAndCacheType() {
        for (String period : List.of("D", "W", "M", "Y")) expectChart(period, "J", "0", CHART);
        for (String period : List.of("D", "W", "M", "Y")) {
            List<PeriodCandle> candles = provider.getPeriodChart(request(period));
            assertEquals(List.of(new PeriodCandle("20261021", "071000", "70000", "72000", "69000"),
                    new PeriodCandle("20261020", "", null, "0", null)), candles);
            String key = key(period);
            assertTrue(((List<?>) redis.values.get(key)).get(0) instanceof ChartStockPrice);
            assertEquals(Long.valueOf(86400), redis.ttls.get(key));
        }
        assertEquals(4, redis.ttls.size());
        server.verify();
    }

    @Test
    public void cachedJsonKeepsOrderAndMissingValuesWithoutHttpOrBudgetUse() {
        useFixedBudget(0);
        redis.values.put(key("D"), List.of(
                Map.of("stck_bsop_date", "20261021", "stck_clpr", "00100"),
                Map.of("stck_bsop_date", "20261020", "stck_clpr", "")));
        assertEquals(List.of(new PeriodCandle("20261021", "00100", null, null, null),
                new PeriodCandle("20261020", "", null, null, null)), provider.getPeriodChart(request("D")));
        assertTrue(redis.ttls.isEmpty());
        assertEquals(0, limiter.getAvailableTokens());
        server.verify();
    }

    @Test
    public void resultDoesNotExposeMutableCachedDto() {
        expectChart("D", "J", "0", CHART);
        List<PeriodCandle> candles = provider.getPeriodChart(request("D"));
        ChartStockPrice cached = (ChartStockPrice) ((List<?>) redis.values.get(key("D"))).get(0);
        cached.setClosePrice("changed");
        assertEquals("071000", candles.get(0).getClosePrice());
        assertEquals("changed", provider.getPeriodChart(request("D")).get(0).getClosePrice());
        assertEquals(1, redis.ttls.size());
        server.verify();
    }

    @Test
    public void startAndEndDatesHaveIndependentCachesAndSameRequestsReuseThem() {
        useFixedBudget(3);
        List<PeriodChartRequest> requests = List.of(request("D"),
                new PeriodChartRequest("005930", "D", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 21)),
                new PeriodChartRequest("005930", "D", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 20)));
        for (int i = 0; i < requests.size(); i++) {
            PeriodChartRequest request = requests.get(i);
            ChartStockPriceRequest expected = legacyRequest("D", "J", "0");
            expected.setStartDate(request.getStartDate().toString().replace("-", ""));
            expected.setEndDate(request.getEndDate().toString().replace("-", ""));
            expectChart(expected, CHART.replace("071000", "07100" + i));
        }
        for (int i = 0; i < requests.size(); i++) {
            assertEquals("07100" + i, provider.getPeriodChart(requests.get(i)).get(0).getClosePrice());
        }
        useFixedBudget(0);
        for (int i = 0; i < requests.size(); i++) {
            assertEquals("07100" + i, provider.getPeriodChart(requests.get(i)).get(0).getClosePrice());
        }
        assertEquals(3, redis.ttls.size());
        assertTrue(redis.ttls.values().stream().allMatch(ttl -> ttl == 86400));
        assertEquals(0, limiter.getAvailableTokens());
        server.verify();
    }

    @Test
    public void marketStockAndAdjustmentHaveIndependentLegacyRequestCaches() {
        useFixedBudget(4);
        ChartStockPriceRequest differentStock = legacyRequest("D", "J", "0");
        differentStock.setStockCode("000660");
        List<ChartStockPriceRequest> requests = List.of(legacyRequest("D", "J", "0"),
                legacyRequest("D", "NX", "0"), differentStock, legacyRequest("D", "J", "1"));
        for (int i = 0; i < requests.size(); i++) {
            expectChart(requests.get(i), CHART.replace("071000", "07100" + i));
        }
        for (int i = 0; i < requests.size(); i++) {
            assertEquals("07100" + i, legacy.getPeriodStockPrices(requests.get(i)).get(0).getClosePrice());
        }
        useFixedBudget(0);
        for (int i = 0; i < requests.size(); i++) {
            assertEquals("07100" + i, legacy.getPeriodStockPrices(requests.get(i)).get(0).getClosePrice());
        }
        assertEquals(4, redis.ttls.size());
        server.verify();
    }

    @Test
    public void effectiveEndpointSeparatesEnvironmentsAndReturningToOneReusesItsCache() {
        useFixedBudget(2);
        expectChart("D", "J", "0", CHART);
        baseUrl = "https://virtual.kis.test";
        expectChart("D", "J", "0", CHART.replace("071000", "072000"));
        baseUrl = "https://kis.test";
        assertEquals("071000", provider.getPeriodChart(request("D")).get(0).getClosePrice());
        baseUrl = "https://virtual.kis.test";
        assertEquals("072000", provider.getPeriodChart(request("D")).get(0).getClosePrice());
        baseUrl = "https://kis.test";
        assertEquals("071000", provider.getPeriodChart(request("D")).get(0).getClosePrice());
        baseUrl = "https://virtual.kis.test";
        assertEquals("072000", provider.getPeriodChart(request("D")).get(0).getClosePrice());
        assertEquals(2, redis.ttls.size());
        assertEquals(0, limiter.getAvailableTokens());
        server.verify();
    }

    @Test
    public void incompleteLegacyAndOtherProviderKeysAreIgnoredAndLeftToExpire() {
        List<Map<String, String>> obsolete = List.of(Map.of("stck_clpr", "obsolete"));
        String oldKey = "stock:chart:D:005930";
        String otherProviderKey = key("D").replaceFirst("kis:", "kiwoom:");
        redis.values.put(oldKey, obsolete);
        redis.values.put(otherProviderKey, obsolete);
        expectChart("D", "J", "0", CHART);
        assertEquals("071000", provider.getPeriodChart(request("D")).get(0).getClosePrice());
        assertEquals(obsolete, redis.values.get(oldKey));
        assertEquals(obsolete, redis.values.get(otherProviderKey));
        assertEquals(1, redis.ttls.size());
        assertEquals(Long.valueOf(86400), redis.ttls.get(key("D")));
        server.verify();
    }

    @Test
    public void delimitersNullAndEmptyComponentsCannotAliasInRepositoryKeys() {
        ChartStockPriceRequest first = legacyRequest("D", "A:B", "0");
        first.setStockCode("C");
        ChartStockPriceRequest second = legacyRequest("D", "A", "0");
        second.setStockCode("B:C");
        List<ChartStockPriceRequest> requests = List.of(first, second, legacyRequest("D", null, "0"),
                legacyRequest("D", "", "0"), legacyRequest("D", "null", "0"));
        for (int i = 0; i < requests.size(); i++) {
            ChartStockPrice candle = new ChartStockPrice();
            candle.setClosePrice(String.valueOf(i));
            charts.save(baseUrl, requests.get(i), List.of(candle));
        }
        for (int i = 0; i < requests.size(); i++) {
            assertEquals(String.valueOf(i), charts.getChartData(baseUrl, requests.get(i)).get().get(0).getClosePrice());
        }
        assertEquals(5, redis.ttls.size());
        server.verify();
    }

    @Test
    public void nullRowsRemainNullInsteadOfIntroducingMappingFailure() {
        expectChart("D", "J", "0", "{\"output2\":[null]}");
        List<PeriodCandle> candles = provider.getPeriodChart(request("D"));
        assertEquals(1, candles.size());
        assertNull(candles.get(0));
        assertEquals(1, provider.getPeriodChart(request("D")).size());
        assertNull(provider.getPeriodChart(request("D")).get(0));
        server.verify();
    }

    @Test
    public void emptyRemoteListIsCachedAndReusedWithExhaustedBudget() {
        useFixedBudget(1);
        expectChart("D", "J", "0", "{\"output2\":[]}");
        assertTrue(provider.getPeriodChart(request("D")).isEmpty());
        assertTrue(provider.getPeriodChart(request("D")).isEmpty());
        assertEquals(List.of(), redis.values.get(key("D")));
        assertEquals(Long.valueOf(86400), redis.ttls.get(key("D")));
        server.verify();
    }

    @Test
    public void sharedBudgetStillRejectsUncachedLegacyAndProviderCalls() {
        useFixedBudget(1);
        expectChart("D", "J", "0", CHART);
        provider.getPeriodChart(request("D"));
        assertThrows(RateLimitExceededException.class, () -> legacy.getCurrentPrice(
                CurrentPriceRequest.builder().stockCode("000660").marketCode("J").build()));
        assertThrows(RateLimitExceededException.class, () -> provider.getPeriodChart(request("W")));
        assertEquals(2, provider.getPeriodChart(request("D")).size());
        assertFalse(redis.values.containsKey(key("W")));
        server.verify();
    }

    @Test
    public void missingOutputOrBodyStillFailsWithoutWritingCache() {
        expectChart("D", "J", "0", "{}");
        server.expect(request -> assertEquals(url("W", "J", "0"), request.getURI().toString()))
                .andRespond(withStatus(HttpStatus.OK));
        assertThrows(RuntimeException.class, () -> provider.getPeriodChart(request("D")));
        assertThrows(RuntimeException.class, () -> provider.getPeriodChart(request("W")));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void remoteFailurePropagatesWithoutRetryOrCacheWrite() {
        server.expect(request -> assertEquals(url("D", "J", "0"), request.getURI().toString()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(HttpServerErrorException.class, () -> provider.getPeriodChart(request("D")));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void unreadableCacheKeepsExistingRemoteReload() {
        redis.values.put(key("D"), "invalid-cache");
        expectChart("D", "J", "0", CHART);
        assertEquals(2, provider.getPeriodChart(request("D")).size());
        assertEquals(Long.valueOf(86400), redis.ttls.get(key("D")));
        server.verify();
    }

    @Test
    public void legacyRequestAndAdapterConstructorInjectionRemainAvailable() {
        expectChart("D", "NX", "1", CHART);
        assertEquals(2, legacy.getPeriodStockPrices(ChartStockPriceRequest.builder()
                .stockCode("005930").periodCode("D").marketCode("NX").adjPrice("1")
                .startDate("20260101").endDate("20261021").build()).size());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(KisApiService.class, () -> legacy);
            context.register(KisPeriodChartProvider.class);
            context.refresh();
            assertSame(context.getBean(KisPeriodChartProvider.class), context.getBean(PeriodChartProvider.class));
        }
        server.verify();
    }

    private PeriodChartRequest request(String period) {
        return new PeriodChartRequest("005930", period, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 21));
    }

    private void expectChart(String period, String market, String adjusted, String json) {
        expectChart(legacyRequest(period, market, adjusted), json);
    }

    private void expectChart(ChartStockPriceRequest expected, String json) {
        String expectedUrl = url(expected);
        server.expect(request -> {
            assertEquals(expectedUrl, request.getURI().toString());
            assertEquals(HttpMethod.GET, request.getMethod());
            assertEquals("Bearer synthetic-token", request.getHeaders().getFirst("Authorization"));
            assertEquals("synthetic-key", request.getHeaders().getFirst("appkey"));
            assertEquals("synthetic-secret", request.getHeaders().getFirst("appsecret"));
            assertEquals("FHKST03010100", request.getHeaders().getFirst("tr_id"));
            assertEquals("P", request.getHeaders().getFirst("custtype"));
        }).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String url(String period, String market, String adjusted) {
        return url(legacyRequest(period, market, adjusted));
    }

    private String url(ChartStockPriceRequest request) {
        return baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"
                + "?FID_COND_MRKT_DIV_CODE=" + request.getMarketCode() + "&FID_INPUT_ISCD=" + request.getStockCode()
                + "&FID_INPUT_DATE_1=" + request.getStartDate() + "&FID_INPUT_DATE_2=" + request.getEndDate()
                + "&FID_PERIOD_DIV_CODE=" + request.getPeriodCode() + "&FID_ORG_ADJ_PRC=" + request.getAdjPrice();
    }

    private ChartStockPriceRequest legacyRequest(String period, String market, String adjusted) {
        return ChartStockPriceRequest.builder().marketCode(market).stockCode("005930")
                .periodCode(period).startDate("20260101").endDate("20261021").adjPrice(adjusted).build();
    }

    private String key(String period) {
        return "kis:chart:period:v2:16:https://kis.test:1:J:6:005930:1:" + period
                + ":8:20260101:8:20261021:1:0";
    }

    private void useFixedBudget(int remaining) {
        Bucket budget = Bucket.builder().addLimit(Bandwidth.builder().capacity(Math.max(1, remaining))
                .refillIntervally(1, Duration.ofDays(1)).build()).build();
        if (remaining == 0) budget.tryConsume(1);
        ReflectionTestUtils.setField(limiter, "bucket", budget);
    }

    private static class MemoryRedis extends RedisTemplate<String, Object> {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        final Map<String, Long> ttls = new ConcurrentHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, Object> opsForValue() {
            return (ValueOperations<String, Object>) Proxy.newProxyInstance(ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class}, (proxy, method, args) -> {
                        if ("get".equals(method.getName())) return values.get(args[0]);
                        if ("set".equals(method.getName()) && args.length == 4) {
                            values.put((String) args[0], args[1]);
                            ttls.put((String) args[0], ((TimeUnit) args[3]).toSeconds((Long) args[2]));
                            return null;
                        }
                        throw new AssertionError("예상하지 않은 Redis 호출: " + method.getName());
                    });
        }
    }
}
