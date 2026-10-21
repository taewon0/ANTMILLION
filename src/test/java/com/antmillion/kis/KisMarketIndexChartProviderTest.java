package com.antmillion.kis;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.dto.CurrentPriceRequest;
import com.antmillion.kis.dto.MarketIndexPrice;
import com.antmillion.kis.dto.MarketIndexPriceRequest;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisMarketIndexChartRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisMarketIndexChartProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.marketdata.dto.MarketIndexCandle;
import com.antmillion.marketdata.dto.MarketIndexChartRequest;
import com.antmillion.marketdata.provider.MarketIndexChartProvider;
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

/** 실제 KIS 서비스·지수 캐시·limiter를 HTTP·메모리 Redis 대역에만 연결합니다. */
public class KisMarketIndexChartProviderTest {
    private static final String CHART = "{\"output2\":["
            + "{\"stck_bsop_date\":\"20261021\",\"bstp_nmix_prpr\":\"02500.50\",\"bstp_nmix_oprc\":\"2490.25\","
            + "\"bstp_nmix_hgpr\":\"2510.75\",\"bstp_nmix_lwpr\":\"2480.00\"},"
            + "{\"stck_bsop_date\":\"20261020\",\"bstp_nmix_prpr\":\"\",\"bstp_nmix_oprc\":null,"
            + "\"bstp_nmix_hgpr\":\"0\"}]}";
    private MemoryRedis redis;
    private MockRestServiceServer server;
    private KisApiService legacy;
    private MarketIndexChartProvider provider;
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
        legacy = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis), null,
                new KisMarketIndexChartRedisRepository(redis, new ObjectMapper()), null, null, null, limiter);
        provider = new KisMarketIndexChartProvider(legacy);
    }

    @Test
    public void bothIndicesKeepDailyRequestHeadersDecimalsAndCacheType() {
        for (String code : List.of("0001", "1001")) expectChart(code, "D", "U", CHART);
        for (String code : List.of("0001", "1001")) {
            assertEquals(List.of(new MarketIndexCandle("20261021", "02500.50", "2490.25", "2510.75", "2480.00"),
                    new MarketIndexCandle("20261020", "", null, "0", null)), provider.getMarketIndexChart(request(code)));
            String key = "market:index:" + code;
            assertTrue(((List<?>) redis.values.get(key)).get(0) instanceof MarketIndexPrice);
            assertEquals(Long.valueOf(60), redis.ttls.get(key));
        }
        assertEquals(2, redis.ttls.size());
        server.verify();
    }

    @Test
    public void cachedJsonKeepsOrderAndMissingValuesWithoutHttpOrBudgetUse() {
        useFixedBudget(0);
        redis.values.put("market:index:0001", List.of(
                Map.of("stck_bsop_date", "20261021", "bstp_nmix_prpr", "02500.50"),
                Map.of("stck_bsop_date", "20261020", "bstp_nmix_prpr", "")));
        assertEquals(List.of(new MarketIndexCandle("20261021", "02500.50", null, null, null),
                new MarketIndexCandle("20261020", "", null, null, null)), provider.getMarketIndexChart(request("0001")));
        assertTrue(redis.ttls.isEmpty());
        assertEquals(0, limiter.getAvailableTokens());
        server.verify();
    }

    @Test
    public void resultDoesNotExposeCachedDtoAndKeepsExistingDateInsensitiveKey() {
        expectChart("0001", "D", "U", CHART);
        List<MarketIndexCandle> candles = provider.getMarketIndexChart(request("0001"));
        MarketIndexPrice cached = (MarketIndexPrice) ((List<?>) redis.values.get("market:index:0001")).get(0);
        cached.setPresentPrice("changed");
        assertEquals("02500.50", candles.get(0).getCloseValue());
        // 날짜가 캐시 키에 없는 기존 한계는 이번 경계 분리에서 변경하지 않습니다.
        assertEquals("changed", provider.getMarketIndexChart(new MarketIndexChartRequest("0001",
                LocalDate.of(2025, 1, 1))).get(0).getCloseValue());
        assertEquals(1, redis.ttls.size());
        server.verify();
    }

    @Test
    public void nullRowsRemainNullOnRemoteAndCachePaths() {
        expectChart("0001", "D", "U", "{\"output2\":[null]}");
        List<MarketIndexCandle> candles = provider.getMarketIndexChart(request("0001"));
        assertEquals(1, candles.size());
        assertNull(candles.get(0));
        assertEquals(1, provider.getMarketIndexChart(request("0001")).size());
        assertNull(provider.getMarketIndexChart(request("0001")).get(0));
        server.verify();
    }

    @Test
    public void emptyRemoteListIsCachedAndReusedWithExhaustedBudget() {
        useFixedBudget(1);
        expectChart("0001", "D", "U", "{\"output2\":[]}");
        assertTrue(provider.getMarketIndexChart(request("0001")).isEmpty());
        assertTrue(provider.getMarketIndexChart(request("0001")).isEmpty());
        assertEquals(List.of(), redis.values.get("market:index:0001"));
        assertEquals(Long.valueOf(60), redis.ttls.get("market:index:0001"));
        assertEquals(0, limiter.getAvailableTokens());
        server.verify();
    }

    @Test
    public void sharedBudgetStillRejectsUncachedLegacyAndProviderCalls() {
        useFixedBudget(1);
        expectChart("0001", "D", "U", CHART);
        provider.getMarketIndexChart(request("0001"));
        assertThrows(RateLimitExceededException.class, () -> legacy.getCurrentPrice(
                CurrentPriceRequest.builder().stockCode("005930").marketCode("J").build()));
        assertThrows(RateLimitExceededException.class, () -> provider.getMarketIndexChart(request("1001")));
        assertEquals(2, provider.getMarketIndexChart(request("0001")).size());
        assertFalse(redis.values.containsKey("market:index:1001"));
        server.verify();
    }

    @Test
    public void missingOutputOrBodyStillFailsWithoutWritingCache() {
        expectChart("0001", "D", "U", "{}");
        server.expect(request -> assertEquals(url("1001", "D", "U"), request.getURI().toString()))
                .andRespond(withStatus(HttpStatus.OK));
        assertThrows(RuntimeException.class, () -> provider.getMarketIndexChart(request("0001")));
        assertThrows(RuntimeException.class, () -> provider.getMarketIndexChart(request("1001")));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void remoteFailurePropagatesWithoutRetryOrCacheWrite() {
        server.expect(request -> assertEquals(url("0001", "D", "U"), request.getURI().toString()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(HttpServerErrorException.class, () -> provider.getMarketIndexChart(request("0001")));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void unreadableCacheKeepsExistingRemoteReload() {
        redis.values.put("market:index:0001", "invalid-cache");
        expectChart("0001", "D", "U", CHART);
        assertEquals(2, provider.getMarketIndexChart(request("0001")).size());
        assertEquals(Long.valueOf(60), redis.ttls.get("market:index:0001"));
        server.verify();
    }

    @Test
    public void legacyRequestAndAdapterConstructorInjectionRemainAvailable() {
        expectChart("0001", "W", "legacy-market", CHART);
        assertEquals(2, legacy.getMarketIndexPrices(MarketIndexPriceRequest.builder()
                .indexCode("0001").periodCode("W").marketCode("legacy-market").endDate("20261021").build()).size());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(KisApiService.class, () -> legacy);
            context.register(KisMarketIndexChartProvider.class);
            context.refresh();
            assertSame(context.getBean(KisMarketIndexChartProvider.class), context.getBean(MarketIndexChartProvider.class));
        }
        server.verify();
    }

    private MarketIndexChartRequest request(String code) {
        return new MarketIndexChartRequest(code, LocalDate.of(2026, 10, 21));
    }

    private void expectChart(String code, String period, String market, String json) {
        server.expect(request -> {
            assertEquals(url(code, period, market), request.getURI().toString());
            assertEquals(HttpMethod.GET, request.getMethod());
            assertEquals("Bearer synthetic-token", request.getHeaders().getFirst("Authorization"));
            assertEquals("synthetic-key", request.getHeaders().getFirst("appkey"));
            assertEquals("synthetic-secret", request.getHeaders().getFirst("appsecret"));
            assertEquals("FHPUP02120000", request.getHeaders().getFirst("tr_id"));
            assertEquals("P", request.getHeaders().getFirst("custtype"));
        }).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String url(String code, String period, String market) {
        return "https://kis.test/uapi/domestic-stock/v1/quotations/inquire-index-daily-price"
                + "?FID_PERIOD_DIV_CODE=" + period + "&FID_COND_MRKT_DIV_CODE=" + market
                + "&FID_INPUT_ISCD=" + code + "&FID_INPUT_DATE_1=20261021";
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
