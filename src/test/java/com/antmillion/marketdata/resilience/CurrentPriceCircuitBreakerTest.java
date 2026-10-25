package com.antmillion.marketdata.resilience;

import com.antmillion.config.GlobalExceptionHandler;
import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.dto.KisRestRequestScope;
import com.antmillion.kis.exception.KisMarketDataException;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisCurrentPriceProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomMarketDataException;
import com.antmillion.kiwoom.repository.KiwoomCurrentPriceRedisRepository;
import com.antmillion.kiwoom.service.KiwoomCurrentPriceProvider;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomRestRateLimitTest;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.marketdata.config.MarketDataCircuitBreakerConfig;
import com.antmillion.marketdata.exception.MarketDataUnavailableException;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
import com.antmillion.marketdata.resilience.MarketDataCircuitBreakers.Provider;
import com.antmillion.mypage.controller.MyPageController;
import com.antmillion.mypage.service.MyPageService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 실제 현재가 코드·limiter·캐시와 합성 토큰·HTTP·메모리 Redis만 사용합니다. */
@RunWith(Parameterized.class)
public class CurrentPriceCircuitBreakerTest {
    @Parameterized.Parameters(name = "{0}-{1}")
    public static Collection<Object[]> cases() {
        return List.of(new Object[]{Provider.KIS, "real"}, new Object[]{Provider.KIS, "virtual"},
                new Object[]{Provider.KIWOOM, "real"}, new Object[]{Provider.KIWOOM, "virtual"});
    }

    private final Provider id;
    private final String mode;
    private final MarketDataCircuitBreakersTest.MutableClock clock = new MarketDataCircuitBreakersTest.MutableClock();
    private final MarketDataCircuitBreakers breakers = new MarketDataCircuitBreakers(
            new MarketDataCircuitBreakerConfig(true, 10, 2, 50, 30, 1), clock);
    private final MemoryRedis redis = new MemoryRedis();
    private final AtomicInteger tokenReads = new AtomicInteger();
    private final AtomicInteger httpCalls = new AtomicInteger();
    private final AtomicLong nanos = new AtomicLong();
    private CurrentPriceProvider provider;
    private MockRestServiceServer server;
    private String base;
    private boolean failToken;
    private boolean advanceBudget = true;
    private RateLimitedKisApiClient kisLimiter;
    private Bucket kisBudget;
    private KiwoomRestRateLimiter kiwoomLimiter;
    private KiwoomTokenService kiwoomTokens;

    public CurrentPriceCircuitBreakerTest(Provider id, String mode) { this.id = id; this.mode = mode; }

    @Before
    public void setUp() {
        RestTemplate rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        if (id == Provider.KIS) {
            KisConfig config = new KisConfig();
            config.mode = mode;
            config.appKey = "synthetic-key"; config.appSecret = "synthetic-secret";
            config.accountNo = "synthetic-account"; config.acntPrdtCd = "01";
            base = config.getBaseUrl();
            kisLimiter = new RateLimitedKisApiClient(redis, config, new KisRestRateLimitConfig(18, 1));
            kisBudget = budget(1000);
            setKisBudget(kisBudget);
            KisAccessTokenRedisRepository tokens = new KisAccessTokenRedisRepository(redis) {
                @Override public Optional<String> findAccessToken(KisAccessTokenScope scope) {
                    return Optional.of(token());
                }
            };
            provider = new KisCurrentPriceProvider(new KisApiService(config, null, rest, tokens,
                    null, null, null, null, null, kisLimiter, breakers));
        } else {
            KiwoomAccessTokenScope scope = new KiwoomAccessTokenScope(mode, "synthetic-key", "synthetic-secret");
            base = scope.getBaseUrl();
            kiwoomTokens = new KiwoomTokenService(scope, 600, rest, null, null, clock) {
                @Override public String getAccessToken() { return token(); }
            };
            kiwoomLimiter = KiwoomRestRateLimitTest.withClock(new TimeMeter() {
                @Override public long currentTimeNanos() {
                    return advanceBudget ? nanos.getAndAdd(1_000_000_000L) : nanos.get();
                }
                @Override public boolean isWallClockBased() { return false; }
            });
            provider = new KiwoomCurrentPriceProvider(kiwoomTokens, rest, kiwoomLimiter,
                    new KiwoomCurrentPriceRedisRepository(redis, clock), breakers);
        }
    }

    @Test
    public void singleAndListShareStateAndOpenRejectsBeforeBudgetTokenHttpAndCache() {
        remoteFailure();
        respond(withServerError());
        assertTrue(provider.getCurrentPrices(List.of("005930")).isEmpty());
        server.verify();
        assertEquals(CircuitBreaker.State.OPEN, circuit().getState());
        long beforeBudget = remaining();
        int beforeTokens = tokenReads.get();
        int beforeHttp = httpCalls.get();
        redis.failReads = true;
        server.reset();
        MarketDataUnavailableException failure = assertThrows(MarketDataUnavailableException.class,
                () -> provider.getCurrentPrice("000660"));
        assertNull(failure.getCause());
        assertTrue(provider.getCurrentPrices(Collections.nCopies(20, "005930")).isEmpty());
        assertEquals(beforeTokens, tokenReads.get());
        assertEquals(beforeHttp, httpCalls.get());
        assertEquals(beforeBudget, remaining());
        server.verify();
    }

    @Test
    public void successfulProbeAfterThirtySecondsRestoresCurrentPrice() {
        open();
        clock.advance(29);
        assertThrows(MarketDataUnavailableException.class, () -> provider.getCurrentPrice("005930"));
        clock.advance(1); clock.tick();
        respond(success());
        assertEquals(Integer.valueOf(71000), provider.getCurrentPrice("005930"));
        server.verify();
        assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
        assertFalse(redis.values.isEmpty());
    }

    @Test
    public void failedProbeReopensAndDoesNotStoreFailure() {
        open();
        clock.advance(30); clock.tick();
        remoteFailure();
        assertEquals(CircuitBreaker.State.OPEN, circuit().getState());
        assertTrue(redis.values.isEmpty());
        assertThrows(MarketDataUnavailableException.class, () -> provider.getCurrentPrice("005930"));
    }

    @Test
    public void classifiedNonProviderFailuresAreNeitherFailuresNorSuccesses() {
        List<ResponseCreator> ignored = new ArrayList<>();
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
                HttpStatus.TOO_MANY_REQUESTS, HttpStatus.BAD_REQUEST)) ignored.add(withStatus(status));
        ignored.add(withSuccess("broken-json", MediaType.APPLICATION_JSON));
        ignored.add(withSuccess(id == Provider.KIS ? "{\"rt_cd\":\"9\"}" : "{\"return_code\":9}", MediaType.APPLICATION_JSON));
        ignored.add(withSuccess(id == Provider.KIS ? "{}" : "{\"cur_prc\":\"bad\"}", MediaType.APPLICATION_JSON));
        for (ResponseCreator response : ignored) {
            respond(response);
            assertThrows(RuntimeException.class, () -> provider.getCurrentPrice("005930"));
            server.verify();
            assertEquals(0, circuit().getMetrics().getNumberOfBufferedCalls());
        }
        failToken = true;
        server.reset();
        assertThrows(RuntimeException.class, () -> provider.getCurrentPrice("005930"));
        assertEquals(0, circuit().getMetrics().getNumberOfBufferedCalls());
        assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
        server.verify();
    }

    @Test
    public void ignoredTokenFailureInHalfOpenReleasesProbeWithoutFalseRecovery() {
        open();
        clock.advance(30); clock.tick();
        failToken = true;
        server.reset();
        assertThrows(RuntimeException.class, () -> provider.getCurrentPrice("005930"));
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuit().getState());
        assertEquals(0, circuit().getMetrics().getNumberOfBufferedCalls());
        failToken = false;
        respond(success());
        assertEquals(Integer.valueOf(71000), provider.getCurrentPrice("005930"));
        assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
        server.verify();
    }

    @Test
    public void transportFailureIsCountedButSensitiveCauseIsNotRetained() {
        for (int i = 0; i < 2; i++) {
            respond(request -> { throw new IOException("synthetic-sensitive-body"); });
            RuntimeException error = assertThrows(RuntimeException.class, () -> provider.getCurrentPrice("005930"));
            assertNull(error.getCause());
            assertFalse(error.toString().contains("synthetic-sensitive"));
            server.verify();
        }
        assertEquals(CircuitBreaker.State.OPEN, circuit().getState());
    }

    @Test
    public void localBudgetCacheFallbackDoesNotCreateSuccessfulSamples() {
        respond(success());
        assertEquals(Integer.valueOf(71000), provider.getCurrentPrice("005930"));
        server.verify();
        exhaustBudget();
        server.reset();
        int reads = tokenReads.get();
        for (int i = 0; i < 8; i++) assertEquals(Integer.valueOf(71000), provider.getCurrentPrice("005930"));
        assertEquals(1, circuit().getMetrics().getNumberOfBufferedCalls());
        assertEquals(reads, tokenReads.get());
        assertEquals(1, httpCalls.get());
        server.verify();
        restoreBudget();
        remoteFailure();
        assertEquals(CircuitBreaker.State.OPEN, circuit().getState());
        // 정상 캐시가 남아 있어도 OPEN에서 추가 fallback하지 않습니다.
        assertThrows(MarketDataUnavailableException.class, () -> provider.getCurrentPrice("005930"));
    }

    @Test
    public void localBudgetMissDoesNotChangeCircuitOrLookUpToken() {
        exhaustBudget();
        int reads = tokenReads.get();
        assertThrows(RuntimeException.class, () -> provider.getCurrentPrice("005930"));
        assertEquals(reads, tokenReads.get());
        assertEquals(0, circuit().getMetrics().getNumberOfBufferedCalls());
        assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
        server.verify();
    }

    @Test
    public void cacheWriteErrorDoesNotTurnHealthyHttpIntoProviderFailure() {
        redis.failWrites = true;
        respond(success());
        if (id == Provider.KIS) assertThrows(IllegalStateException.class, () -> provider.getCurrentPrice("005930"));
        else assertEquals(Integer.valueOf(71000), provider.getCurrentPrice("005930"));
        server.verify();
        assertEquals(1, circuit().getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, circuit().getMetrics().getNumberOfFailedCalls());
    }

    @Test
    public void emptyListDoesNotAcquireProbeOrQueryExternalSystems() {
        open();
        clock.advance(30); clock.tick();
        int reads = tokenReads.get();
        assertTrue(provider.getCurrentPrices(List.of()).isEmpty());
        assertEquals(CircuitBreaker.State.OPEN, circuit().getState());
        assertEquals(reads, tokenReads.get());
        respond(success());
        assertEquals(Integer.valueOf(71000), provider.getCurrentPrice("005930"));
        server.verify();
    }

    @Test
    public void existingHoldingsHttpPathReturnsSafe503WhenCircuitRejects() throws Exception {
        open();
        MyPageService service = (MyPageService) Proxy.newProxyInstance(MyPageService.class.getClassLoader(),
                new Class<?>[]{MyPageService.class}, (proxy, method, args) -> {
                    if (!"getStockHoldings".equals(method.getName())) throw new AssertionError(method.getName());
                    provider.getCurrentPrice("005930");
                    return List.of();
                });
        org.springframework.mock.web.MockHttpServletResponse response = MockMvcBuilders
                .standaloneSetup(new MyPageController(service, null))
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/mypage/api/stock-holdings")).andReturn().getResponse();
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("Market data unavailable"));
        assertFalse(response.getContentAsString().contains("synthetic"));
    }

    private String token() {
        tokenReads.incrementAndGet();
        if (failToken) throw new IllegalStateException("synthetic-sensitive-storage");
        return "synthetic-token";
    }
    private CircuitBreaker circuit() { return breakers.circuit(id, base); }
    private void open() { remoteFailure(); remoteFailure(); }
    private void remoteFailure() {
        respond(withServerError());
        RuntimeException failure = assertThrows(RuntimeException.class, () -> provider.getCurrentPrice("005930"));
        assertTrue(failure instanceof KisMarketDataException || failure instanceof KiwoomMarketDataException);
        server.verify();
    }
    private ResponseCreator success() {
        return withSuccess(id == Provider.KIS ? "{\"output\":{\"stck_prpr\":\"71000\",\"prdy_ctrt\":\"1.25\"}}"
                : "{\"cur_prc\":\"+71000\",\"flu_rt\":\"1.25\",\"return_code\":0}", MediaType.APPLICATION_JSON);
    }
    private void respond(ResponseCreator response) {
        server.reset();
        server.expect(request -> {
            httpCalls.incrementAndGet();
            assertTrue(request.getURI().toString().startsWith(base));
            assertEquals("Bearer synthetic-token", request.getHeaders().getFirst("Authorization"));
        }).andRespond(response);
    }
    private Bucket budget(long count) {
        Bucket result = Bucket.builder().addLimit(Bandwidth.builder().capacity(Math.max(count, 1))
                .refillIntervally(Math.max(count, 1), Duration.ofDays(1)).build()).build();
        if (count == 0) result.tryConsume(1);
        return result;
    }
    @SuppressWarnings("unchecked")
    private void setKisBudget(Bucket budget) {
        ((Map<String, Bucket>) ReflectionTestUtils.getField(kisLimiter, "buckets")).put(
                new KisRestRequestScope(base, "synthetic-account", "01", "synthetic-key", "synthetic-secret").getBudgetKey(), budget);
    }
    private void exhaustBudget() {
        if (id == Provider.KIS) setKisBudget(budget(0));
        else {
            advanceBudget = false;
            nanos.addAndGet(1_000_000_000L);
            int capacity = mode.equals("real") ? 5 : 1;
            for (int i = 0; i < capacity; i++) kiwoomLimiter.acquireCurrentPrice(kiwoomTokens);
        }
    }
    private void restoreBudget() {
        if (id == Provider.KIS) setKisBudget(kisBudget);
        else { advanceBudget = true; nanos.addAndGet(1_000_000_000L); }
    }
    @SuppressWarnings("unchecked")
    private long remaining() {
        if (id == Provider.KIS) return kisBudget.getAvailableTokens();
        advanceBudget = false;
        Map<String, Bucket> buckets = (Map<String, Bucket>) ReflectionTestUtils.getField(kiwoomLimiter, "buckets");
        return buckets.get(kiwoomTokens.getCurrentPriceBudgetKey()).getAvailableTokens();
    }

    private static class MemoryRedis extends RedisTemplate<String, Object> {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        boolean failReads;
        boolean failWrites;
        @Override @SuppressWarnings("unchecked")
        public ValueOperations<String, Object> opsForValue() {
            return (ValueOperations<String, Object>) Proxy.newProxyInstance(ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class}, (proxy, method, args) -> {
                        if ("get".equals(method.getName())) {
                            if (failReads) throw new IllegalStateException("synthetic-read");
                            return values.get(args[0]);
                        }
                        if ("set".equals(method.getName()) && args.length == 4) {
                            if (failWrites) throw new IllegalStateException("synthetic-write");
                            values.put((String) args[0], args[1]);
                            return null;
                        }
                        throw new AssertionError(method.getName());
                    });
        }
    }
}
