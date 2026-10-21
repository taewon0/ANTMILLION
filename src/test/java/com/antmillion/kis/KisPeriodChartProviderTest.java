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

    @Before
    public void setUp() {
        redis = new MemoryRedis();
        redis.values.put("kis:access_token", "synthetic-token");
        RestTemplate rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        KisConfig config = new KisConfig() {
            @Override public String getBaseUrl() { return "https://kis.test"; }
        };
        config.appKey = "synthetic-key";
        config.appSecret = "synthetic-secret";
        limiter = new RateLimitedKisApiClient(redis);
        legacy = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis),
                new KisChartRedisRepository(redis, new ObjectMapper()), null, null, null, null, limiter);
        provider = new KisPeriodChartProvider(legacy);
    }

    @Test
    public void allPeriodsKeepKrxAdjustedRequestHeadersStringsAndCacheType() {
        for (String period : List.of("D", "W", "M", "Y")) expectChart(period, "J", "0", CHART);
        for (String period : List.of("D", "W", "M", "Y")) {
            List<PeriodCandle> candles = provider.getPeriodChart(request(period));
            assertEquals(List.of(new PeriodCandle("20261021", "071000", "70000", "72000", "69000"),
                    new PeriodCandle("20261020", "", null, "0", null)), candles);
            String key = "stock:chart:" + period + ":005930";
            assertTrue(((List<?>) redis.values.get(key)).get(0) instanceof ChartStockPrice);
            assertEquals(Long.valueOf(86400), redis.ttls.get(key));
        }
        assertEquals(4, redis.ttls.size());
        server.verify();
    }

    @Test
    public void cachedJsonKeepsOrderAndMissingValuesWithoutHttpOrBudgetUse() {
        useFixedBudget(0);
        redis.values.put("stock:chart:D:005930", List.of(
                Map.of("stck_bsop_date", "20261021", "stck_clpr", "00100"),
                Map.of("stck_bsop_date", "20261020", "stck_clpr", "")));
        assertEquals(List.of(new PeriodCandle("20261021", "00100", null, null, null),
                new PeriodCandle("20261020", "", null, null, null)), provider.getPeriodChart(request("D")));
        assertTrue(redis.ttls.isEmpty());
        assertEquals(0, limiter.getAvailableTokens());
        server.verify();
    }

    @Test
    public void resultDoesNotExposeMutableCachedDtoAndKeepsExistingDateInsensitiveKey() {
        expectChart("D", "J", "0", CHART);
        List<PeriodCandle> candles = provider.getPeriodChart(request("D"));
        ChartStockPrice cached = (ChartStockPrice) ((List<?>) redis.values.get("stock:chart:D:005930")).get(0);
        cached.setClosePrice("changed");
        assertEquals("071000", candles.get(0).getClosePrice());
        // 날짜가 캐시 키에 없는 기존 한계는 이번 경계 분리에서 변경하지 않습니다.
        PeriodChartRequest differentDates = new PeriodChartRequest("005930", "D",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1));
        assertEquals("changed", provider.getPeriodChart(differentDates).get(0).getClosePrice());
        assertEquals(1, redis.ttls.size());
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
        assertEquals(List.of(), redis.values.get("stock:chart:D:005930"));
        assertEquals(Long.valueOf(86400), redis.ttls.get("stock:chart:D:005930"));
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
        assertFalse(redis.values.containsKey("stock:chart:W:005930"));
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
        redis.values.put("stock:chart:D:005930", "invalid-cache");
        expectChart("D", "J", "0", CHART);
        assertEquals(2, provider.getPeriodChart(request("D")).size());
        assertEquals(Long.valueOf(86400), redis.ttls.get("stock:chart:D:005930"));
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
        server.expect(request -> {
            assertEquals(url(period, market, adjusted), request.getURI().toString());
            assertEquals(HttpMethod.GET, request.getMethod());
            assertEquals("Bearer synthetic-token", request.getHeaders().getFirst("Authorization"));
            assertEquals("synthetic-key", request.getHeaders().getFirst("appkey"));
            assertEquals("synthetic-secret", request.getHeaders().getFirst("appsecret"));
            assertEquals("FHKST03010100", request.getHeaders().getFirst("tr_id"));
            assertEquals("P", request.getHeaders().getFirst("custtype"));
        }).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String url(String period, String market, String adjusted) {
        return "https://kis.test/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"
                + "?FID_COND_MRKT_DIV_CODE=" + market + "&FID_INPUT_ISCD=005930"
                + "&FID_INPUT_DATE_1=20260101&FID_INPUT_DATE_2=20261021"
                + "&FID_PERIOD_DIV_CODE=" + period + "&FID_ORG_ADJ_PRC=" + adjusted;
    }

    private void useFixedBudget(int remaining) {
        Bucket budget = Bucket.builder().addLimit(Bandwidth.builder().capacity(1)
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
