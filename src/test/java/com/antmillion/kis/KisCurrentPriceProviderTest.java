package com.antmillion.kis;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.dto.CurrentPrice;
import com.antmillion.kis.dto.CurrentPriceRequest;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.exception.KisMarketDataException;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisCurrentPriceProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.marketdata.dto.CurrentQuote;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 KIS 호출 코드를 HTTP·Redis 대역에만 연결하며 애플리케이션 설정은 로드하지 않습니다. */
public class KisCurrentPriceProviderTest {
    private MemoryRedis redis;
    private RestTemplate rest;
    private MockRestServiceServer server;
    private KisApiService legacy;
    private CurrentPriceProvider provider;
    private RateLimitedKisApiClient limiter;
    private String baseUrl;

    @Before
    public void setUp() {
        redis = new MemoryRedis();
        for (String endpoint : List.of("https://kis.test", "https://virtual.kis.test")) {
            redis.values.put(new KisAccessTokenScope(endpoint, "synthetic-key", "synthetic-secret").getTokenKey(),
                    "synthetic-token");
        }
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        baseUrl = "https://kis.test";
        KisConfig config = new KisConfig() {
            @Override public String getBaseUrl() { return baseUrl; }
        };
        config.appKey = "synthetic-key";
        config.appSecret = "synthetic-secret";
        config.accountNo = "synthetic-account";
        config.acntPrdtCd = "01";
        limiter = new RateLimitedKisApiClient(redis, config, new KisRestRateLimitConfig(18, 1));
        legacy = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis),
                null, null, null, null, null, limiter);
        provider = new KisCurrentPriceProvider(legacy);
    }

    @Test
    public void singlePriceKeepsKrxRequestTokenAndIntegerCache() {
        expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"71000\",\"prdy_ctrt\":\"1.25\"}}");
        assertEquals(Integer.valueOf(71000), provider.getCurrentPrice("005930"));
        assertEquals(71000, redis.values.get(key(false)));
        assertEquals(Long.valueOf(10), redis.ttls.get(key(false)));
        assertFalse(redis.values.containsKey(key(true)));
        server.verify();
    }

    @Test
    public void listKeepsOrderStringsAndPartialFailureWithoutLeakingCachedDtos() {
        expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"071000\",\"prdy_ctrt\":\"-1.20\"}}");
        server.expect(request -> assertEquals(url("000660", "J"), request.getURI().toString()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectQuote("035420", "J", "{\"output\":{\"stck_prpr\":\"0\",\"prdy_ctrt\":null}}");

        List<CurrentQuote> quotes = provider.getCurrentPrices(List.of("005930", "000660", "035420"));
        assertEquals(List.of(new CurrentQuote("005930", "071000", "-1.20"),
                new CurrentQuote("035420", "0", null)), quotes);
        CurrentPrice cached = (CurrentPrice) redis.values.get(key(true));
        assertNull(cached.getStockName());
        assertEquals(Long.valueOf(10), redis.ttls.get(key(true)));
        cached.setCurrentPrice("changed");
        assertEquals("071000", quotes.get(0).getCurrentPrice());
        assertFalse(redis.values.containsKey(key(false)));
        server.verify();
    }

    @Test
    public void emptyListDoesNotCallHttpOrWriteCache() {
        assertTrue(provider.getCurrentPrices(List.of()).isEmpty());
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void exhaustedSharedBudgetKeepsIntegerCacheFallback() {
        useFixedBudget(1);
        expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"71000\"}}");
        assertEquals(Integer.valueOf(71000), provider.getCurrentPrice("005930"));
        // 같은 limiter를 사용하는 기존 호출도 추가 HTTP 요청 없이 같은 정수 캐시를 사용합니다.
        assertEquals(Integer.valueOf(71000), legacy.getCurrentPrice(request("005930", "J")));
        server.verify();
    }

    @Test
    public void exhaustedBudgetKeepsDetailedCacheTypeAndMissingValue() {
        useFixedBudget(0);
        CurrentPrice cached = new CurrentPrice();
        cached.setStockCode("005930");
        cached.setStockName("캐시에 남은 이름");
        cached.setPrdyCtrt("+0.00");
        redis.values.put(key(true), cached);
        List<CurrentQuote> quotes = provider.getCurrentPrices(List.of("005930"));
        assertEquals(List.of(new CurrentQuote("005930", null, "+0.00")), quotes);
        assertSame(cached, redis.values.get(key(true)));
        assertEquals("캐시에 남은 이름", cached.getStockName());
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void exhaustedBudgetWithoutCachePreservesSingleFailureAndListOmission() {
        useFixedBudget(0);
        assertThrows(RateLimitExceededException.class, () -> provider.getCurrentPrice("005930"));
        assertTrue(provider.getCurrentPrices(List.of("005930")).isEmpty());
        server.verify();
    }

    @Test
    public void remoteFailureDoesNotIntroduceNewStaleFallback() {
        redis.values.put(key(false), 1000);
        server.expect(request -> assertEquals(url("005930", "J"), request.getURI().toString()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertEquals(KisMarketDataException.Kind.SERVER,
                assertThrows(KisMarketDataException.class, () -> provider.getCurrentPrice("005930")).getKind());
        assertEquals(1000, redis.values.get(key(false)));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void invalidSinglePayloadStillFailsWithoutWritingCache() {
        expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"invalid\"}}");
        assertEquals(KisMarketDataException.Kind.INVALID_RESPONSE,
                assertThrows(KisMarketDataException.class, () -> provider.getCurrentPrice("005930")).getKind());
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void listKeepsValidDuplicatesAndDoesNotLogRawFailure() {
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(KisApiService.class);
        org.apache.log4j.Level previous = logger.getLevel();
        java.io.StringWriter output = new java.io.StringWriter();
        org.apache.log4j.WriterAppender appender = new org.apache.log4j.WriterAppender(
                new org.apache.log4j.PatternLayout("%m%n"), output);
        logger.addAppender(appender);
        logger.setLevel(org.apache.log4j.Level.ERROR);
        try {
            server.expect(request -> assertEquals(url("000660", "J"), request.getURI().toString()))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("synthetic-sensitive-body"));
            expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"071000\"}}");
            expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"071000\"}}");
            assertEquals(List.of(new CurrentQuote("005930", "071000", null), new CurrentQuote("005930", "071000", null)),
                    provider.getCurrentPrices(List.of("000660", "005930", "005930")));
            assertTrue(output.toString().contains("000660"));
            for (String secret : List.of("synthetic-sensitive", "synthetic-token", "synthetic-key", "synthetic-secret")) {
                assertFalse(output.toString().contains(secret));
            }
            assertEquals(1, redis.ttls.size());
            server.verify();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.close();
        }
    }

    @Test
    public void legacyRequestContractAndAdapterBeanRemainAvailable() {
        expectQuote("005930", "NX", "{\"output\":{\"stck_prpr\":\"71000\"}}");
        assertEquals(Integer.valueOf(71000), legacy.getCurrentPrice(request("005930", "NX")));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // root context나 설정 scan 없이 실제 adapter의 생성자 주입만 검증합니다.
            context.registerBean(KisApiService.class, () -> legacy);
            context.register(KisCurrentPriceProvider.class);
            context.refresh();
            assertSame(context.getBean(KisCurrentPriceProvider.class), context.getBean(CurrentPriceProvider.class));
        }
        server.verify();
    }

    @Test
    public void singleJThenNxKeepsIndependentFallbacks() {
        assertIndependentFallbacks(false, "J", "NX", false);
    }

    @Test
    public void singleNxThenJKeepsIndependentFallbacks() {
        assertIndependentFallbacks(false, "NX", "J", false);
    }

    @Test
    public void detailJThenNxKeepsIndependentFallbacks() {
        assertIndependentFallbacks(true, "J", "NX", false);
    }

    @Test
    public void detailNxThenJKeepsIndependentFallbacks() {
        assertIndependentFallbacks(true, "NX", "J", false);
    }

    @Test
    public void singleEndpointsKeepIndependentFallbacks() {
        assertIndependentFallbacks(false, "J", "J", true);
    }

    @Test
    public void detailEndpointsKeepIndependentFallbacks() {
        assertIndependentFallbacks(true, "J", "J", true);
    }

    private void assertIndependentFallbacks(boolean detail, String firstMarket, String secondMarket,
                                            boolean changeEndpoint) {
        expectQuote("005930", firstMarket, "{\"output\":{\"stck_prpr\":\"71000\"}}");
        if (changeEndpoint) baseUrl = "https://virtual.kis.test";
        expectQuote("005930", secondMarket, "{\"output\":{\"stck_prpr\":\"72000\"}}");
        baseUrl = "https://kis.test";
        useFixedBudget(1);
        assertEquals("71000", loadPrice(detail, "005930", firstMarket));
        if (changeEndpoint) baseUrl = "https://virtual.kis.test";
        useFixedBudget(1);
        assertEquals("72000", loadPrice(detail, "005930", secondMarket));
        useFixedBudget(0);
        redis.ttls.clear();
        baseUrl = "https://kis.test";
        assertEquals("71000", loadPrice(detail, "005930", firstMarket));
        if (changeEndpoint) baseUrl = "https://virtual.kis.test";
        assertEquals("72000", loadPrice(detail, "005930", secondMarket));
        assertTrue(redis.ttls.isEmpty());
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        server.verify();
    }

    private String loadPrice(boolean detail, String code, String market) {
        return detail ? legacy.getCurrentPriceDetail(request(code, market)).getCurrentPrice()
                : legacy.getCurrentPrice(request(code, market)).toString();
    }

    @Test
    public void singleAndDetailKeepIndependentTypesAndStockCodes() {
        for (String code : List.of("005930", "000660")) {
            expectQuote(code, "J", "{\"output\":{\"stck_prpr\":\"71000\"}}");
            expectQuote(code, "J", "{\"output\":{\"stck_prpr\":\"072000\"}}");
        }
        for (String code : List.of("005930", "000660")) {
            useFixedBudget(1);
            assertEquals("71000", loadPrice(false, code, "J"));
            useFixedBudget(1);
            assertEquals("072000", loadPrice(true, code, "J"));
        }
        useFixedBudget(0);
        for (String code : List.of("005930", "000660")) {
            assertEquals("71000", loadPrice(false, code, "J"));
            assertEquals("072000", loadPrice(true, code, "J"));
        }
        assertTrue(redis.values.get(key(false)) instanceof Integer);
        assertTrue(redis.values.get(key(true)) instanceof CurrentPrice);
        assertEquals(4, redis.ttls.size());
        assertTrue(redis.ttls.values().stream().allMatch(ttl -> ttl == 10));
        server.verify();
    }

    @Test
    public void legacyAndOtherProviderKeysAreNotReadOrModified() {
        Map<String, Object> previous = Map.of(
                "current:price:005930", 1,
                "current:price:detail:005930", new CurrentPrice(),
                key(false).replace("kis:", "kiwoom:"), 2,
                key(true).replace("kis:", "kiwoom:"), new CurrentPrice());
        redis.values.putAll(previous);
        useFixedBudget(0);
        assertThrows(RateLimitExceededException.class, () -> loadPrice(false, "005930", "J"));
        assertThrows(RateLimitExceededException.class, () -> loadPrice(true, "005930", "J"));
        previous.forEach((oldKey, value) -> assertSame(value, redis.values.get(oldKey)));
        assertTrue(redis.ttls.isEmpty());
        expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"71000\"}}");
        expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"71000\"}}");
        for (boolean detail : new boolean[]{false, true}) {
            useFixedBudget(1);
            assertEquals("71000", loadPrice(detail, "005930", "J"));
        }
        previous.forEach((oldKey, value) -> {
            assertSame(value, redis.values.get(oldKey));
            assertFalse(redis.ttls.containsKey(oldKey));
        });
        assertEquals(2, redis.ttls.size());
        server.verify();
    }

    @Test
    public void availableBudgetStillCallsRemoteInsteadOfReadingCache() {
        for (boolean detail : new boolean[]{false, true}) {
            expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"71000\"}}");
            expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"72000\"}}");
        }
        for (boolean detail : new boolean[]{false, true}) {
            useFixedBudget(1);
            assertEquals("71000", loadPrice(detail, "005930", "J"));
            useFixedBudget(1);
            assertEquals("72000", loadPrice(detail, "005930", "J"));
            useFixedBudget(0);
            assertEquals("72000", loadPrice(detail, "005930", "J"));
        }
        server.verify();
    }

    @Test
    public void delimiterNullAndEmptyConditionsKeepIndependentFallbacks() {
        List<CurrentPriceRequest> requests = List.of(request("005930", "J:X"), request("X:005930", "J"),
                request("005930", null), request("005930", ""), request("005930", "null"));
        for (boolean detail : new boolean[]{false, true}) {
            for (int i = 0; i < requests.size(); i++) {
                CurrentPriceRequest request = requests.get(i);
                expectQuote(request.getStockCode(), request.getMarketCode(),
                        "{\"output\":{\"stck_prpr\":\"" + (71000 + i) + "\"}}");
            }
        }
        for (boolean detail : new boolean[]{false, true}) {
            for (int i = 0; i < requests.size(); i++) {
                CurrentPriceRequest request = requests.get(i);
                useFixedBudget(1);
                assertEquals(String.valueOf(71000 + i),
                        loadPrice(detail, request.getStockCode(), request.getMarketCode()));
            }
            useFixedBudget(0);
            for (int i = 0; i < requests.size(); i++) {
                CurrentPriceRequest request = requests.get(i);
                assertEquals(String.valueOf(71000 + i),
                        loadPrice(detail, request.getStockCode(), request.getMarketCode()));
            }
        }
        assertEquals(10, redis.ttls.size());
        server.verify();
    }

    private String key(boolean detail) {
        return "kis:price:" + (detail ? "detail" : "single") + ":v2:16:https://kis.test:1:J:6:005930";
    }

    private void expectQuote(String code, String market, String json) {
        String expectedUrl = url(code, market);
        server.expect(request -> {
            assertEquals(expectedUrl, request.getURI().toString());
            assertEquals(HttpMethod.GET, request.getMethod());
            assertEquals("Bearer synthetic-token", request.getHeaders().getFirst("Authorization"));
            assertEquals("synthetic-key", request.getHeaders().getFirst("appkey"));
            assertEquals("synthetic-secret", request.getHeaders().getFirst("appsecret"));
            assertEquals("FHKST01010100", request.getHeaders().getFirst("tr_id"));
            assertEquals("P", request.getHeaders().getFirst("custtype"));
        }).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String url(String code, String market) {
        return baseUrl + "/uapi/domestic-stock/v1/quotations/inquire-price"
                + "?FID_COND_MRKT_DIV_CODE" + (market == null ? "" : "=" + market) + "&FID_INPUT_ISCD=" + code;
    }

    private CurrentPriceRequest request(String code, String market) {
        return CurrentPriceRequest.builder().stockCode(code).marketCode(market).build();
    }

    private void useFixedBudget(int remaining) {
        // 벽시계 대기 없이 제한·캐시 분기를 검증하도록 긴 주기의 테스트 버킷만 주입합니다.
        Bucket budget = Bucket.builder().addLimit(Bandwidth.builder().capacity(1)
                .refillIntervally(1, Duration.ofDays(1)).build()).build();
        if (remaining == 0) budget.tryConsume(1);
        KisRateLimitTestSupport.fixedCacheBudget(limiter, budget);
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
