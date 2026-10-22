package com.antmillion.kis;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.dto.KisAccessTokenScope;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
    private KisMarketIndexChartRedisRepository charts;
    private String baseUrl;

    @Before
    public void setUp() {
        redis = new MemoryRedis();
        for (String endpoint : List.of("https://kis.test", "https://virtual.kis.test")) {
            redis.values.put(new KisAccessTokenScope(endpoint, "synthetic-key", "synthetic-secret").getTokenKey(),
                    "synthetic-token");
        }
        RestTemplate rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        baseUrl = "https://kis.test";
        KisConfig config = new KisConfig() {
            @Override public String getBaseUrl() { return baseUrl; }
        };
        config.appKey = "synthetic-key";
        config.appSecret = "synthetic-secret";
        limiter = new RateLimitedKisApiClient(redis);
        charts = new KisMarketIndexChartRedisRepository(redis, new ObjectMapper());
        legacy = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis), null,
                charts, null, null, null, limiter);
        provider = new KisMarketIndexChartProvider(legacy);
    }

    @Test
    public void newerDateThenOlderDateKeepIndependentCaches() {
        assertDateCachesRemainIndependent(LocalDate.of(2026, 10, 21), LocalDate.of(2025, 1, 1));
    }

    @Test
    public void olderDateThenNewerDateKeepIndependentCaches() {
        assertDateCachesRemainIndependent(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 21));
    }

    private void assertDateCachesRemainIndependent(LocalDate firstDate, LocalDate secondDate) {
        MarketIndexChartRequest first = new MarketIndexChartRequest("0001", firstDate);
        MarketIndexChartRequest second = new MarketIndexChartRequest("0001", secondDate);
        server.expect(request -> assertEquals(url("0001", "D", "U").replace("20261021",
                firstDate.toString().replace("-", "")), request.getURI().toString()))
                .andRespond(withSuccess(CHART, MediaType.APPLICATION_JSON));
        server.expect(request -> assertEquals(url("0001", "D", "U").replace("20261021",
                secondDate.toString().replace("-", "")), request.getURI().toString()))
                .andRespond(withSuccess(CHART.replace("02500.50", "02600.75"), MediaType.APPLICATION_JSON));
        assertEquals("02500.50", provider.getMarketIndexChart(first).get(0).getCloseValue());
        assertEquals("02600.75", provider.getMarketIndexChart(second).get(0).getCloseValue());
        useFixedBudget(0);
        assertEquals("02500.50", provider.getMarketIndexChart(first).get(0).getCloseValue());
        assertEquals("02600.75", provider.getMarketIndexChart(second).get(0).getCloseValue());
        assertEquals(2, redis.ttls.size());
        server.verify();
    }

    @Test
    public void bothIndicesKeepDailyRequestHeadersDecimalsAndCacheType() {
        for (String code : List.of("0001", "1001")) expectChart(code, "D", "U", CHART);
        for (String code : List.of("0001", "1001")) {
            assertEquals(List.of(new MarketIndexCandle("20261021", "02500.50", "2490.25", "2510.75", "2480.00"),
                    new MarketIndexCandle("20261020", "", null, "0", null)), provider.getMarketIndexChart(request(code)));
            String key = key(code);
            assertTrue(((List<?>) redis.values.get(key)).get(0) instanceof MarketIndexPrice);
            assertEquals(Long.valueOf(60), redis.ttls.get(key));
        }
        assertEquals(2, redis.ttls.size());
        server.verify();
    }

    @Test
    public void cachedJsonKeepsOrderAndMissingValuesWithoutHttpOrBudgetUse() {
        useFixedBudget(0);
        redis.values.put(key("0001"), List.of(
                Map.of("stck_bsop_date", "20261021", "bstp_nmix_prpr", "02500.50"),
                Map.of("stck_bsop_date", "20261020", "bstp_nmix_prpr", "")));
        assertEquals(List.of(new MarketIndexCandle("20261021", "02500.50", null, null, null),
                new MarketIndexCandle("20261020", "", null, null, null)), provider.getMarketIndexChart(request("0001")));
        assertTrue(redis.ttls.isEmpty());
        assertEquals(0, limiter.getAvailableTokens());
        server.verify();
    }

    @Test
    public void resultDoesNotExposeMutableCachedDto() {
        expectChart("0001", "D", "U", CHART);
        List<MarketIndexCandle> candles = provider.getMarketIndexChart(request("0001"));
        MarketIndexPrice cached = (MarketIndexPrice) ((List<?>) redis.values.get(key("0001"))).get(0);
        cached.setPresentPrice("changed");
        assertEquals("02500.50", candles.get(0).getCloseValue());
        assertEquals("changed", provider.getMarketIndexChart(request("0001")).get(0).getCloseValue());
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
        assertEquals(List.of(), redis.values.get(key("0001")));
        assertEquals(Long.valueOf(60), redis.ttls.get(key("0001")));
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
        assertFalse(redis.values.containsKey(key("1001")));
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
        redis.values.put(key("0001"), "invalid-cache");
        expectChart("0001", "D", "U", CHART);
        assertEquals(2, provider.getMarketIndexChart(request("0001")).size());
        assertEquals(Long.valueOf(60), redis.ttls.get(key("0001")));
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

    @Test
    public void effectiveEndpointSeparatesEnvironmentsAndReturningReusesTheCache() {
        expectChart("0001", "D", "U", CHART);
        baseUrl = "https://virtual.kis.test";
        expectChart("0001", "D", "U", CHART.replace("02500.50", "02600.75"));
        baseUrl = "https://kis.test";
        assertEquals("02500.50", provider.getMarketIndexChart(request("0001")).get(0).getCloseValue());
        baseUrl = "https://virtual.kis.test";
        assertEquals("02600.75", provider.getMarketIndexChart(request("0001")).get(0).getCloseValue());
        useFixedBudget(0);
        baseUrl = "https://kis.test";
        assertEquals("02500.50", provider.getMarketIndexChart(request("0001")).get(0).getCloseValue());
        baseUrl = "https://virtual.kis.test";
        assertEquals("02600.75", provider.getMarketIndexChart(request("0001")).get(0).getCloseValue());
        assertEquals(2, redis.ttls.size());
        server.verify();
    }

    @Test
    public void everyQueryConditionHasAnIndependentCache() {
        List<Consumer<MarketIndexPriceRequest>> changes = List.of(
                r -> r.setPeriodCode("W"), r -> r.setMarketCode("NX"),
                r -> r.setIndexCode("1001"), r -> r.setEndDate("20261020"));
        List<MarketIndexPriceRequest> requests = new ArrayList<>();
        requests.add(legacyRequest());
        for (Consumer<MarketIndexPriceRequest> change : changes) {
            MarketIndexPriceRequest request = legacyRequest();
            change.accept(request);
            requests.add(request);
        }
        for (int i = 0; i < requests.size(); i++) {
            MarketIndexPriceRequest request = requests.get(i);
            String expectedUrl = url(request.getIndexCode(), request.getPeriodCode(), request.getMarketCode())
                    .replace("20261021", request.getEndDate());
            server.expect(http -> assertEquals(expectedUrl, http.getURI().toString()))
                    .andRespond(withSuccess(CHART.replace("02500.50", String.valueOf(i)), MediaType.APPLICATION_JSON));
        }
        for (int i = 0; i < requests.size(); i++) {
            assertEquals(String.valueOf(i), legacy.getMarketIndexPrices(requests.get(i)).get(0).getPresentPrice());
        }
        useFixedBudget(0);
        for (int i = 0; i < requests.size(); i++) {
            assertEquals(String.valueOf(i), legacy.getMarketIndexPrices(requests.get(i)).get(0).getPresentPrice());
        }
        assertEquals(5, redis.ttls.size());
        assertTrue(redis.ttls.values().stream().allMatch(ttl -> ttl == 60));
        server.verify();
    }

    @Test
    public void incompleteLegacyAndOtherProviderKeysAreIgnoredAndLeftToExpire() {
        List<Map<String, String>> obsolete = List.of(Map.of("bstp_nmix_prpr", "obsolete"));
        String oldKey = "market:index:0001";
        String otherProviderKey = key("0001").replaceFirst("kis:", "kiwoom:");
        redis.values.put(oldKey, obsolete);
        redis.values.put(otherProviderKey, obsolete);
        expectChart("0001", "D", "U", CHART);
        assertEquals("02500.50", provider.getMarketIndexChart(request("0001")).get(0).getCloseValue());
        assertEquals(obsolete, redis.values.get(oldKey));
        assertEquals(obsolete, redis.values.get(otherProviderKey));
        assertEquals(1, redis.ttls.size());
        server.verify();
    }

    @Test
    public void delimitersNullAndEmptyConditionsCannotAliasInRepositoryKeys() {
        MarketIndexPriceRequest first = legacyRequest();
        first.setPeriodCode("A:B");
        first.setMarketCode("C");
        MarketIndexPriceRequest second = legacyRequest();
        second.setPeriodCode("A");
        second.setMarketCode("B:C");
        List<MarketIndexPriceRequest> requests = new ArrayList<>(List.of(first, second));
        for (String value : new String[]{null, "", "null"}) {
            MarketIndexPriceRequest request = legacyRequest();
            request.setPeriodCode(value);
            requests.add(request);
        }
        for (int i = 0; i < requests.size(); i++) {
            MarketIndexPrice row = new MarketIndexPrice();
            row.setPresentPrice(String.valueOf(i));
            charts.save(baseUrl, requests.get(i), List.of(row));
        }
        for (int i = 0; i < requests.size(); i++) {
            assertEquals(String.valueOf(i), charts.getChartData(baseUrl, requests.get(i))
                    .get().get(0).getPresentPrice());
        }
        assertEquals(5, redis.ttls.size());
        server.verify();
    }

    private MarketIndexPriceRequest legacyRequest() {
        return MarketIndexPriceRequest.builder().periodCode("D").marketCode("U")
                .indexCode("0001").endDate("20261021").build();
    }

    private String key(String code) {
        StringBuilder key = new StringBuilder("kis:chart:market-index:v2");
        for (String value : new String[]{baseUrl, "D", "U", code, "20261021"}) {
            key.append(':').append(value.length()).append(':').append(value);
        }
        return key.toString();
    }

    private void expectChart(String code, String period, String market, String json) {
        String expectedUrl = url(code, period, market);
        server.expect(request -> {
            assertEquals(expectedUrl, request.getURI().toString());
            assertEquals(HttpMethod.GET, request.getMethod());
            assertEquals("Bearer synthetic-token", request.getHeaders().getFirst("Authorization"));
            assertEquals("synthetic-key", request.getHeaders().getFirst("appkey"));
            assertEquals("synthetic-secret", request.getHeaders().getFirst("appsecret"));
            assertEquals("FHPUP02120000", request.getHeaders().getFirst("tr_id"));
            assertEquals("P", request.getHeaders().getFirst("custtype"));
        }).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String url(String code, String period, String market) {
        return baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-index-daily-price"
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
