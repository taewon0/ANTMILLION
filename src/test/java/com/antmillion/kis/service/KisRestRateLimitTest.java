package com.antmillion.kis.service;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.constant.KisApiConstant;
import com.antmillion.kis.dto.CurrentPriceRequest;
import com.antmillion.kis.dto.DayMinutePriceRequest;
import com.antmillion.kis.dto.KisRestRequestScope;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import io.github.bucket4j.TimeMeter;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 합성 계좌·고정 단조 시각·메모리 Redis·HTTP 대역만 사용하며 개발 설정을 로드하지 않습니다. */
public class KisRestRateLimitTest {
    private static final String REAL = KisApiConstant.REAL_BASE_URL;
    private static final String VIRTUAL = KisApiConstant.VIRTUAL_BASE_URL;
    private final AtomicLong nanos = new AtomicLong();
    private final TimeMeter clock = new TimeMeter() {
        @Override public long currentTimeNanos() { return nanos.get(); }
        @Override public boolean isWallClockBased() { return false; }
    };
    private MemoryRedis redis;
    private KisConfig config;
    private RateLimitedKisApiClient limiter;

    @Before
    public void setUp() {
        redis = new MemoryRedis();
        config = new KisConfig();
        config.mode = "real";
        config.accountNo = "synthetic-account";
        config.acntPrdtCd = "01";
        config.appKey = "synthetic-key";
        config.appSecret = "synthetic-secret";
        limiter = new RateLimitedKisApiClient(redis, config, new KisRestRateLimitConfig(18, 1), clock);
    }

    @Test
    public void defaultsUse18RealAnd1VirtualAndRefillWithMonotonicTime() {
        KisRestRequestScope real = scope(REAL, "account-a", "01");
        KisRestRequestScope virtual = scope(VIRTUAL, "account-a", "01");
        assertEquals(18, limiter.getAvailableTokens(real));
        assertEquals(1, limiter.getAvailableTokens(virtual));
        for (int i = 0; i < 18; i++) limiter.callWithoutCache(real, () -> "ok");
        limiter.callWithoutCache(virtual, () -> "ok");
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RateLimitExceededException.class, () -> limiter.callWithoutCache(real, calls::incrementAndGet));
        assertThrows(RateLimitExceededException.class, () -> limiter.callWithoutCache(virtual, calls::incrementAndGet));
        assertEquals(0, calls.get());
        nanos.set(TimeUnit.SECONDS.toNanos(1));
        assertEquals(18, limiter.getAvailableTokens(real));
        assertEquals(1, limiter.getAvailableTokens(virtual));
    }

    @Test
    public void exhaustingEitherEnvironmentDoesNotSpendTheOtherBudget() {
        for (String first : List.of(REAL, VIRTUAL)) {
            String second = first.equals(REAL) ? VIRTUAL : REAL;
            KisRestRequestScope a = scope(first, first, "01");
            KisRestRequestScope b = scope(second, first, "01");
            int capacity = first.equals(REAL) ? 18 : 1;
            for (int i = 0; i < capacity; i++) limiter.callWithoutCache(a, () -> "ok");
            assertEquals("other", limiter.callWithoutCache(b, () -> "other"));
            assertEquals(0, limiter.getAvailableTokens(a));
        }
    }

    @Test
    public void kiwoomAndKisNeverSpendEachOthersBudget() {
        com.antmillion.kiwoom.service.KiwoomRestRateLimiter kiwoom =
                com.antmillion.kiwoom.service.KiwoomRestRateLimitTest.withClock(clock);
        for (boolean kisFirst : List.of(true, false)) {
            String identity = kisFirst ? "kis-first" : "kiwoom-first";
            KisRestRequestScope kisScope = scope(VIRTUAL, identity, "01");
            com.antmillion.kiwoom.service.KiwoomTokenService tokens =
                    new com.antmillion.kiwoom.service.KiwoomTokenService(
                            new com.antmillion.kiwoom.dto.KiwoomAccessTokenScope("virtual", identity, "secret"),
                            600, null, null, null, null);
            if (kisFirst) limiter.callWithoutCache(kisScope, () -> "ok");
            kiwoom.acquireCurrentPrice(tokens);
            if (!kisFirst) assertEquals("ok", limiter.callWithoutCache(kisScope, () -> "ok"));
            assertThrows(com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException.class,
                    () -> kiwoom.acquireCurrentPrice(tokens));
            assertThrows(RateLimitExceededException.class, () -> limiter.callWithoutCache(kisScope, () -> "unexpected"));
        }
        assertEquals(0, redis.reads);
    }

    @Test
    public void credentialsCanRotateWithoutResettingTheAccountBudget() {
        KisRestRequestScope first = scope(VIRTUAL, "account-a", "01");
        KisRestRequestScope rotated = new KisRestRequestScope(VIRTUAL, "account-a", "01", "other-key", "other-secret");
        assertEquals(first.getBudgetKey(), rotated.getBudgetKey());
        assertNotEquals(first.getTokenScope().getTokenKey(), rotated.getTokenScope().getTokenKey());
        limiter.callWithoutCache(first, () -> "ok");
        assertThrows(RateLimitExceededException.class, () -> limiter.callWithoutCache(rotated, () -> "unexpected"));
    }

    @Test
    public void differentAccountsProductsAndActualEndpointsHaveIndependentBudgets() {
        for (KisRestRequestScope scope : List.of(scope(VIRTUAL, "account-a", "01"),
                scope(VIRTUAL, "account-b", "01"), scope(VIRTUAL, "account-a", "02"),
                scope("https://alternate.kis.test", "account-a", "01"))) {
            assertEquals("ok", limiter.callWithoutCache(scope, () -> "ok"));
        }
    }

    @Test
    public void budgetIdentityHidesSecretsAndKeepsComponentBoundaries() {
        KisRestRequestScope first = scope(REAL, "account:a", "b");
        assertNotEquals(first.getBudgetKey(), scope(REAL, "account", "a:b").getBudgetKey());
        assertEquals(first.getBudgetKey(), scope(REAL, " account:a ", " b ").getBudgetKey());
        assertTrue(first.getBudgetKey().matches("[0-9a-f]{64}"));
        assertFalse(first.toString().contains("synthetic"));
        assertFalse(first.getBudgetKey().contains("account"));
    }

    @Test
    public void blankAccountOrProductIsRejectedBeforeAnyRedisAccess() {
        for (String invalid : new String[]{null, "", "   "}) {
            assertThrows(IllegalArgumentException.class, () -> scope(REAL, invalid, "01"));
            assertThrows(IllegalArgumentException.class, () -> scope(REAL, "account-a", invalid));
        }
        config.accountNo = " ";
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimitedKisApiClient(redis, config, new KisRestRateLimitConfig(18, 1)));
        assertEquals(0, redis.reads);
    }

    @Test
    public void springDefaultsAndExplicitLowerLimitAreResolvedAtStartup() {
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            context.refresh();
            assertEquals(18, context.getBean(KisRestRateLimitConfig.class).tokensPerSecond(REAL));
            assertEquals(1, context.getBean(KisRestRateLimitConfig.class).tokensPerSecond(VIRTUAL));
        }
        try (AnnotationConfigApplicationContext context = context(Map.of("kis.rest.rate-limit.real-per-second", "3"))) {
            context.refresh();
            KisRestRateLimitConfig limits = context.getBean(KisRestRateLimitConfig.class);
            assertEquals(3, limits.tokensPerSecond(REAL));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("later",
                    Map.of("kis.rest.rate-limit.real-per-second", "18")));
            assertEquals(3, limits.tokensPerSecond(REAL));
        }
    }

    @Test
    public void springRejectsNonNumericAndOutOfPolicyLimitsAtStartup() {
        for (String invalid : List.of("0", "-1", "19", "2147483648", "", "abc")) {
            try (AnnotationConfigApplicationContext context = context(Map.of("kis.rest.rate-limit.real-per-second", invalid))) {
                assertThrows(RuntimeException.class, context::refresh);
            }
        }
        for (String invalid : List.of("0", "2", "-1")) {
            try (AnnotationConfigApplicationContext context = context(Map.of("kis.rest.rate-limit.virtual-per-second", invalid))) {
                assertThrows(RuntimeException.class, context::refresh);
            }
        }
    }

    @Test
    public void springInjectsTheProductionConstructorAndConfiguredBudget() {
        try (AnnotationConfigApplicationContext context = context(Map.of("kis.rest.rate-limit.real-per-second", "3"))) {
            // 등록된 합성 singleton만 사용해 KisConfig의 개발 PropertySource를 처리하지 않습니다.
            context.getBeanFactory().registerSingleton("kisConfig", config);
            context.getBeanFactory().registerSingleton("syntheticRedis", redis);
            context.register(RateLimitedKisApiClient.class);
            context.refresh();
            assertEquals(3, context.getBean(RateLimitedKisApiClient.class)
                    .getAvailableTokens(scope(REAL, "account-a", "01")));
        }
    }

    @Test
    public void springRejectsMissingAccountBeforeCreatingTheClient() {
        config.acntPrdtCd = "";
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            context.getBeanFactory().registerSingleton("kisConfig", config);
            context.getBeanFactory().registerSingleton("syntheticRedis", redis);
            context.register(RateLimitedKisApiClient.class);
            assertThrows(RuntimeException.class, context::refresh);
            assertEquals(0, redis.reads);
        }
    }

    @Test
    public void concurrentFirstRequestsShareOneBudget() throws Exception {
        KisRestRequestScope scope = scope(REAL, "account-a", "01");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) results.add(workers.submit(() -> {
                start.await();
                try {
                    limiter.callWithoutCache(scope, calls::incrementAndGet);
                    return true;
                } catch (RateLimitExceededException expected) {
                    return false;
                }
            }));
            start.countDown();
            int allowed = 0;
            for (Future<Boolean> result : results) if (result.get(5, TimeUnit.SECONDS)) allowed++;
            assertEquals(18, allowed);
            assertEquals(18, calls.get());
            assertEquals(0, limiter.getAvailableTokens(scope));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void cacheAndNoCacheCallsShareBudgetAndCachedFallbackKeepsTtl() {
        KisRestRequestScope scope = scope(VIRTUAL, "account-a", "01");
        assertEquals("fresh", limiter.callWithCache(scope, "synthetic-cache", () -> "fresh", 10));
        assertEquals(Long.valueOf(10), redis.ttls.get("synthetic-cache"));
        assertThrows(RateLimitExceededException.class, () -> limiter.callWithoutCache(scope, () -> "unexpected"));
        assertEquals("fresh", limiter.callWithCache(scope, "synthetic-cache", () -> "unexpected", 99));
        assertEquals(Long.valueOf(10), redis.ttls.get("synthetic-cache"));
    }

    @Test
    public void failedExternalCallStillSpendsOnlyItsOwnBudget() {
        KisRestRequestScope scope = scope(VIRTUAL, "account-a", "01");
        IllegalStateException error = new IllegalStateException("synthetic-failure");
        assertSame(error, assertThrows(IllegalStateException.class,
                () -> limiter.callWithoutCache(scope, () -> { throw error; })));
        assertEquals(0, limiter.getAvailableTokens(scope));
        assertEquals(1, limiter.getAvailableTokens(scope(VIRTUAL, "account-b", "01")));
    }

    @Test
    public void configuredLowerRealLimitIsActuallyEnforced() {
        RateLimitedKisApiClient lower = new RateLimitedKisApiClient(redis, config,
                new KisRestRateLimitConfig(3, 1), clock);
        KisRestRequestScope scope = scope(REAL, "account-a", "01");
        for (int i = 0; i < 3; i++) lower.callWithoutCache(scope, () -> "ok");
        assertThrows(RateLimitExceededException.class, () -> lower.callWithoutCache(scope, () -> "unexpected"));
        nanos.set(TimeUnit.SECONDS.toNanos(1));
        assertEquals(3, lower.getAvailableTokens(scope));
    }

    @Test
    public void exhaustedServiceScopeSkipsTokenAndHttpWhileAnotherAccountStillWorks() {
        config.mode = "virtual";
        KisRestRequestScope exhausted = scope(VIRTUAL, config.accountNo, "01");
        limiter.callWithoutCache(exhausted, () -> "spent");
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        KisApiService service = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis),
                null, null, null, null, null, limiter, com.antmillion.marketdata.CircuitBreakerTestSupport.defaults());
        DayMinutePriceRequest request = DayMinutePriceRequest.builder().condMrktDivCode("J")
                .inputIscd("005930").pwDataIncuYn("Y").etcClsCode("0").build();
        assertThrows(RateLimitExceededException.class, () -> service.getDayMinutePrices(request));
        assertEquals(0, redis.reads);
        config.accountNo = "account-b";
        KisRestRequestScope other = scope(VIRTUAL, "account-b", "01");
        redis.values.put(other.getTokenScope().getTokenKey(), "synthetic-token");
        server.expect(http -> assertTrue(http.getURI().toString().startsWith(VIRTUAL + KisApiConstant.DAY_MINUTE_PATH)))
                .andRespond(withSuccess("{\"output2\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(service.getDayMinutePrices(request).isEmpty());
        assertEquals(0, limiter.getAvailableTokens(other));
        assertEquals(0, limiter.getAvailableTokens(exhausted));
        server.verify();
    }

    @Test
    public void cacheRetryConsumesRefilledBudgetBeforeCallingExternalSupplier() throws Exception {
        KisRestRequestScope scope = scope(VIRTUAL, "account-a", "01");
        limiter.callWithoutCache(scope, () -> "spent");
        // 리필 시각만 전진시킵니다. callWithCache의 기존 1회 대기는 그대로 실행합니다.
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch cacheRead = new CountDownLatch(1);
        redis.onRead = cacheRead::countDown;
        try {
            Future<String> result = worker.submit(() -> limiter.callWithCache(scope, "synthetic-cache", () -> "fresh", 10));
            assertTrue(cacheRead.await(5, TimeUnit.SECONDS));
            nanos.set(TimeUnit.SECONDS.toNanos(1));
            assertEquals("fresh", result.get(5, TimeUnit.SECONDS));
            assertEquals(0, limiter.getAvailableTokens(scope));
            assertEquals(Long.valueOf(10), redis.ttls.get("synthetic-cache"));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void interruptedCacheRetryRestoresInterruptAndNeverRunsCallback() {
        KisRestRequestScope scope = scope(VIRTUAL, "account-a", "01");
        limiter.callWithoutCache(scope, () -> "spent");
        AtomicInteger calls = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            assertThrows(RateLimitExceededException.class,
                    () -> limiter.callWithCache(scope, null, calls::incrementAndGet, 10));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, calls.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void serviceUsesOneSnapshotForBudgetTokenHeadersUrlAndCache() {
        KisRestRequestScope original = scope(REAL, config.accountNo, config.acntPrdtCd);
        redis.values.put(original.getTokenScope().getTokenKey(), "synthetic-token");
        RateLimitedKisApiClient changing = new RateLimitedKisApiClient(redis, config,
                new KisRestRateLimitConfig(18, 1), clock) {
            @Override public <T> T callWithCache(KisRestRequestScope scope, String key, Supplier<T> call, long ttl) {
                assertEquals(original.getBudgetKey(), scope.getBudgetKey());
                config.mode = "virtual";
                config.accountNo = "account-b";
                config.appKey = "rotated-key";
                config.appSecret = "rotated-secret";
                return super.callWithCache(scope, key, call, ttl);
            }
        };
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(request -> {
            assertTrue(request.getURI().toString().startsWith(REAL + KisApiConstant.PRESENT_PRICE));
            assertEquals("synthetic-key", request.getHeaders().getFirst("appkey"));
            assertEquals("synthetic-secret", request.getHeaders().getFirst("appsecret"));
            assertEquals("Bearer synthetic-token", request.getHeaders().getFirst("Authorization"));
        }).andRespond(withSuccess("{\"output\":{\"stck_prpr\":\"71000\"}}", MediaType.APPLICATION_JSON));
        KisApiService service = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis),
                null, null, null, null, null, changing, com.antmillion.marketdata.CircuitBreakerTestSupport.defaults());
        assertEquals(Integer.valueOf(71000), service.getCurrentPrice(
                CurrentPriceRequest.builder().marketCode("J").stockCode("005930").build()));
        assertEquals(17, changing.getAvailableTokens(original));
        assertEquals(1, changing.getAvailableTokens(scope(VIRTUAL, "account-b", "01")));
        assertTrue(redis.ttls.keySet().stream().allMatch(key -> key.contains(REAL)));
        server.verify();
    }

    private KisRestRequestScope scope(String endpoint, String account, String product) {
        return new KisRestRequestScope(endpoint, account, product, "synthetic-key", "synthetic-secret");
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> settings) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic", settings));
        context.register(KisRestRateLimitConfig.class);
        return context;
    }

    private static class MemoryRedis extends RedisTemplate<String, Object> {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        final Map<String, Long> ttls = new ConcurrentHashMap<>();
        int reads;
        Runnable onRead = () -> { };

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, Object> opsForValue() {
            return (ValueOperations<String, Object>) Proxy.newProxyInstance(ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class}, (proxy, method, args) -> {
                        if ("get".equals(method.getName())) { reads++; onRead.run(); return values.get(args[0]); }
                        if ("set".equals(method.getName())) {
                            values.put((String) args[0], args[1]);
                            ttls.put((String) args[0], ((TimeUnit) args[3]).toSeconds((Long) args[2]));
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
