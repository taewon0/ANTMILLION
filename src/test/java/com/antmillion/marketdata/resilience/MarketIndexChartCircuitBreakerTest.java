package com.antmillion.marketdata.resilience;

import com.antmillion.config.GlobalExceptionHandler;
import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.dto.KisRestRequestScope;
import com.antmillion.kis.exception.KisMarketDataException;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisMarketIndexChartRedisRepository;
import com.antmillion.kis.repository.KisChartRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisCurrentPriceProvider;
import com.antmillion.kis.service.KisMarketIndexChartProvider;
import com.antmillion.kis.service.KisPeriodChartProvider;
import com.antmillion.kis.service.KisMinuteChartProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomMarketDataException;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomCurrentPriceRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomMarketIndexChartRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomPeriodChartRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomDailyMinuteRedisRepository;
import com.antmillion.kiwoom.service.KiwoomCurrentPriceProvider;
import com.antmillion.kiwoom.service.KiwoomMarketIndexChartProvider;
import com.antmillion.kiwoom.service.KiwoomPeriodChartProvider;
import com.antmillion.kiwoom.service.KiwoomMinuteChartProvider;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomRestRateLimitTest;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.marketdata.config.MarketDataCircuitBreakerConfig;
import com.antmillion.marketdata.dto.MarketIndexCandle;
import com.antmillion.marketdata.dto.MarketIndexChartRequest;
import com.antmillion.marketdata.dto.PeriodChartRequest;
import com.antmillion.marketdata.dto.MinuteChartRequest;
import com.antmillion.marketdata.exception.MarketDataUnavailableException;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
import com.antmillion.marketdata.provider.MarketIndexChartProvider;
import com.antmillion.marketdata.provider.PeriodChartProvider;
import com.antmillion.marketdata.provider.MinuteChartProvider;
import com.antmillion.marketdata.resilience.MarketDataCircuitBreakers.Provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 실제 양 Provider·캐시·limiter·CB에 합성 토큰/HTTP·메모리 Redis만 연결합니다. */
@RunWith(Parameterized.class)
public class MarketIndexChartCircuitBreakerTest {
    @Parameterized.Parameters(name = "{0}-{1}")
    public static Collection<Object[]> cases() {
        return List.of(new Object[]{Provider.KIS, "real"}, new Object[]{Provider.KIS, "virtual"},
                new Object[]{Provider.KIWOOM, "real"}, new Object[]{Provider.KIWOOM, "virtual"});
    }

    private static final LocalDate END = LocalDate.of(2026, 10, 23);
    private static final Clock QUERY_CLOCK = Clock.fixed(Instant.parse("2026-10-23T06:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final Provider id;
    private final String mode;
    private final MarketDataCircuitBreakersTest.MutableClock clock = new MarketDataCircuitBreakersTest.MutableClock();
    private final MarketDataCircuitBreakers breakers = new MarketDataCircuitBreakers(
            new MarketDataCircuitBreakerConfig(true, 10, 2, 50, 30, 1), clock);
    private Fixture f;

    public MarketIndexChartCircuitBreakerTest(Provider id, String mode) { this.id = id; this.mode = mode; }

    @Before
    public void setUp() { f = new Fixture(id, mode); }

    @Test
    public void bothIndexesCountRemoteSuccessAndCacheHitsUseNoBudgetTokenOrSamples() {
        for (String index : List.of("0001", "1001")) {
            f.respond(f.chartSuccess(index));
            assertEquals(candles(), f.chart.getMarketIndexChart(request(index)));
            f.server.verify();
        }
        assertEquals(2, f.circuit().getMetrics().getNumberOfSuccessfulCalls());
        int reads = f.tokenReads;
        Map<String, Long> budget = f.budgets();
        f.server.reset();
        for (String index : List.of("0001", "1001")) {
            assertEquals(candles(), f.chart.getMarketIndexChart(request(index)));
        }
        assertEquals(reads, f.tokenReads);
        assertEquals(budget, f.budgets());
        assertEquals(2, f.httpCalls);
        assertEquals(2, f.circuit().getMetrics().getNumberOfBufferedCalls());
        f.server.verify();
    }

    @Test
    public void currentAndIndexFailuresCombineAndOpenRejectsBothUncachedIndexesBeforeIo() {
        f.currentFailure();
        f.chartFailure();
        assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
        int reads = f.tokenReads;
        Map<String, Long> budget = f.budgets();
        f.server.reset();
        for (String index : List.of("0001", "1001")) {
            assertThrows(MarketDataUnavailableException.class, () -> f.chart.getMarketIndexChart(request(index)));
        }
        assertThrows(MarketDataUnavailableException.class, () -> f.current.getCurrentPrice("005930"));
        assertEquals(reads, f.tokenReads);
        assertEquals(budget, f.budgets());
        assertEquals(2, f.httpCalls);
        assertEquals(0, f.redis.writes);
        f.server.verify();
    }

    @Test
    public void chartFailuresBlockCurrentAndCurrentRecoveryRestoresCharts() {
        f.chartFailure(); f.chartFailure();
        assertThrows(MarketDataUnavailableException.class, () -> f.current.getCurrentPrice("005930"));
        probeTime();
        f.respond(f.currentSuccess());
        assertEquals(Integer.valueOf(71000), f.current.getCurrentPrice("005930"));
        assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
        f.server.verify();
        f.respond(f.chartSuccess("1001"));
        assertEquals(candles(), f.chart.getMarketIndexChart(request("1001")));
        f.server.verify();
    }

    @Test
    public void currentFailuresBlockChartAndChartProbeRestoresCurrent() {
        f.open();
        clock.advance(30);
        assertThrows(MarketDataUnavailableException.class, () -> f.chart.getMarketIndexChart(request("0001")));
        clock.tick();
        f.respond(f.chartSuccess("0001"));
        assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
        assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
        f.server.verify();
        f.respond(f.currentSuccess());
        assertEquals(Integer.valueOf(71000), f.current.getCurrentPrice("005930"));
        f.server.verify();
    }

    @Test
    public void cacheHitsDuringOpenAndHalfOpenNeitherProbeNorRecover() {
        f.respond(f.chartSuccess("0001"));
        assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
        f.server.verify();
        f.circuit().reset();
        f.open();
        f.server.reset();
        int reads = f.tokenReads;
        Map<String, Long> budget = f.budgets();
        assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
        assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
        probeTime();
        // 캐시만 읽는 요청은 OPEN 대기 만료 후에도 탐색을 시작하지 않습니다.
        assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
        assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
        try (MarketDataCircuitBreakers.Call pending = breakers.acquire(id, f.base)) {
            assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
            assertEquals(CircuitBreaker.State.HALF_OPEN, f.circuit().getState());
            assertThrows(MarketDataUnavailableException.class, () -> f.chart.getMarketIndexChart(request("1001")));
            assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
        }
        assertEquals(reads, f.tokenReads);
        assertEquals(budget, f.budgets());
        f.server.verify();
        f.respond(f.chartSuccess("1001"));
        assertEquals(candles(), f.chart.getMarketIndexChart(request("1001")));
        assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
        f.server.verify();
    }

    @Test
    public void failedChartProbeReopensAndDoesNotCacheFailure() {
        f.open(); probeTime();
        f.chartFailure();
        assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
        assertEquals(0, f.redis.writes);
        assertThrows(MarketDataUnavailableException.class, () -> f.chart.getMarketIndexChart(request("1001")));
    }

    @Test
    public void otherProviderAndEnvironmentRemainCallableWhenThisCircuitIsOpen() {
        f.open();
        List<Fixture> others = List.of(new Fixture(id, mode.equals("real") ? "virtual" : "real"),
                new Fixture(id == Provider.KIS ? Provider.KIWOOM : Provider.KIS, mode));
        for (Fixture other : others) {
            other.respond(other.chartSuccess("0001"));
            assertEquals(candles(), other.chart.getMarketIndexChart(request("0001")));
            assertEquals(CircuitBreaker.State.CLOSED, other.circuit().getState());
            assertEquals(1, other.circuit().getMetrics().getNumberOfSuccessfulCalls());
            other.server.verify();
        }
        assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
    }

    @Test
    public void nonProviderFailuresNeverEnterSuccessOrFailureDenominator() {
        List<ResponseCreator> ignored = new ArrayList<>();
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
                HttpStatus.TOO_MANY_REQUESTS, HttpStatus.BAD_REQUEST)) ignored.add(withStatus(status));
        ignored.add(withSuccess("broken-json", MediaType.APPLICATION_JSON));
        ignored.add(withSuccess(id == Provider.KIS ? "{\"rt_cd\":\"9\"}" : "{\"return_code\":9}", MediaType.APPLICATION_JSON));
        ignored.add(withSuccess("{}", MediaType.APPLICATION_JSON));
        if (id == Provider.KIWOOM) ignored.add(withSuccess("{\"inds_dt_pole_qry\":[{\"dt\":\"20261023\",\"cur_prc\":\"bad\"}]}", MediaType.APPLICATION_JSON));
        for (ResponseCreator response : ignored) {
            f.respond(response);
            assertThrows(RuntimeException.class, () -> f.chart.getMarketIndexChart(request("0001")));
            f.server.verify();
            assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
        }
        f.failToken = true;
        f.server.reset();
        f.nanos.addAndGet(1_000_000_000L);
        assertThrows(RuntimeException.class, () -> f.chart.getMarketIndexChart(request("0001")));
        assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
        assertEquals(0, f.redis.writes);
        f.server.verify();
    }

    @Test
    public void transportFailuresAreCountedWithoutRetainingSensitiveCause() {
        for (int i = 0; i < 2; i++) {
            f.respond(request -> { throw new IOException("synthetic-sensitive-cause"); });
            RuntimeException error = assertThrows(RuntimeException.class, () -> f.chart.getMarketIndexChart(request("0001")));
            assertNull(error.getCause());
            assertFalse(error.toString().contains("synthetic-sensitive"));
            f.server.verify();
        }
        assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
    }

    @Test
    public void localRateLimitNeverCountsOrReadsToken() {
        f.exhaustChartBudget();
        f.assertLimited();
        assertEquals(0, f.tokenReads);
        assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
        assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
        f.server.verify();
    }

    @Test
    public void rateLimitInHalfOpenReturnsPermitAndNextChartCanRecover() {
        f.open(); probeTime();
        int reads = f.tokenReads;
        f.exhaustChartBudget();
        f.assertLimited();
        assertEquals(reads, f.tokenReads);
        assertEquals(CircuitBreaker.State.HALF_OPEN, f.circuit().getState());
        assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
        f.restoreBudget();
        f.respond(f.chartSuccess("0001"));
        assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
        assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
        f.server.verify();
    }

    @Test
    public void tokenFailureInHalfOpenReturnsPermitWithoutFalseRecovery() {
        f.open(); probeTime();
        f.failToken = true;
        f.nanos.addAndGet(1_000_000_000L);
        f.server.reset();
        assertThrows(RuntimeException.class, () -> f.chart.getMarketIndexChart(request("0001")));
        assertEquals(CircuitBreaker.State.HALF_OPEN, f.circuit().getState());
        assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
        f.server.verify();
        f.failToken = false;
        f.respond(f.chartSuccess("0001"));
        assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
        assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
        f.server.verify();
    }

    @Test
    public void cacheWriteFailurePreservesHealthyRemoteSampleAndExistingResponsePolicy() {
        f.redis.failWrites = true;
        f.respond(f.chartSuccess("0001"));
        if (id == Provider.KIS) assertThrows(IllegalStateException.class, () -> f.chart.getMarketIndexChart(request("0001")));
        else assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
        assertEquals(1, f.circuit().getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, f.circuit().getMetrics().getNumberOfFailedCalls());
        f.server.verify();
    }

    @Test
    public void cacheReadFailureKeepsExistingPolicyAndDoesNotProduceFalseSamples() {
        f.redis.failReads = true;
        if (id == Provider.KIS) {
            assertThrows(IllegalStateException.class, () -> f.chart.getMarketIndexChart(request("0001")));
            assertEquals(0, f.tokenReads);
            assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
        } else {
            f.respond(f.chartSuccess("0001"));
            assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
            assertEquals(1, f.circuit().getMetrics().getNumberOfSuccessfulCalls());
        }
        f.server.verify();
    }

    @Test
    public void indexCacheExpiresAtSixtySecondsAndMissNeedsAnAvailableProbe() {
        f.respond(f.chartSuccess("0001"));
        assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
        assertEquals(60000L, f.redis.lastTtlMillis);
        f.server.verify(); f.circuit().reset(); f.open(); f.server.reset();
        clock.advance(59);
        assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
        assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
        int reads = f.tokenReads;
        Map<String, Long> budget = f.budgets();
        try (MarketDataCircuitBreakers.Call pending = breakers.acquire(id, f.base)) {
            clock.advance(1);
            assertThrows(MarketDataUnavailableException.class, () -> f.chart.getMarketIndexChart(request("0001")));
            assertEquals(CircuitBreaker.State.HALF_OPEN, f.circuit().getState());
            assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
        }
        assertEquals(reads, f.tokenReads);
        assertEquals(budget, f.budgets());
        f.respond(f.chartSuccess("0001"));
        assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
        assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
        f.server.verify();
    }

    @Test
    public void ignoredRemoteErrorsReleaseHalfOpenPermitWithoutRecovering() {
        for (ResponseCreator ignored : List.of(withStatus(HttpStatus.UNAUTHORIZED),
                withStatus(HttpStatus.TOO_MANY_REQUESTS), withSuccess("{}", MediaType.APPLICATION_JSON))) {
            fresh(); f.open(); probeTime();
            f.respond(ignored);
            assertThrows(RuntimeException.class, () -> f.chart.getMarketIndexChart(request("0001")));
            assertEquals(CircuitBreaker.State.HALF_OPEN, f.circuit().getState());
            assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
            f.server.verify();
            f.respond(f.chartSuccess("1001"));
            assertEquals(candles(), f.chart.getMarketIndexChart(request("1001")));
            assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
            f.server.verify();
        }
    }

    @Test
    public void periodAndMinuteFailuresCombineWithIndexAndIndexProbeRecoversBoth() {
        for (String operation : List.of("period", "daily", "recent")) {
            fresh();
            f.respond(withServerError());
            f.assertClassified(assertThrows(RuntimeException.class, () -> otherQuery(operation)));
            f.server.verify(); f.chartFailure();
            assertEveryPathBlocked(); probeTime();
            f.respond(f.chartSuccess("0001"));
            assertEquals(candles(), f.chart.getMarketIndexChart(request("0001")));
            assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
            f.server.verify(); f.respond(f.otherSuccess(operation));
            assertEquals(Collections.emptyList(), otherQuery(operation));
            f.server.verify();
        }
    }

    @Test
    public void indexFailuresBlockPeriodAndMinutesAndTheirProbeRecoversIndex() {
        for (String operation : List.of("period", "daily", "recent")) {
            fresh(); f.chartFailure(); f.chartFailure();
            assertEveryPathBlocked(); probeTime();
            f.respond(f.otherSuccess(operation));
            assertEquals(Collections.emptyList(), otherQuery(operation));
            assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
            f.server.verify(); f.respond(f.chartSuccess("1001"));
            assertEquals(candles(), f.chart.getMarketIndexChart(request("1001")));
            f.server.verify();
        }
    }

    @Test
    public void emptyRemoteChartIsHealthyAndCached() {
        f.respond(withSuccess(id == Provider.KIS ? "{\"output2\":[]}" : "{\"inds_dt_pole_qry\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(f.chart.getMarketIndexChart(request("0001")).isEmpty());
        f.server.verify();
        f.server.reset();
        assertTrue(f.chart.getMarketIndexChart(request("0001")).isEmpty());
        assertEquals(1, f.circuit().getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(1, f.httpCalls);
        f.server.verify();
    }

    @Test
    public void existingMarketIndexHttpPathReturnsSafe503() throws Exception {
        f.open();
        org.springframework.mock.web.MockHttpServletResponse response = MockMvcBuilders
                .standaloneSetup(new KisController(null, null, null, null, f.chart, null))
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/api/kis/marketIndex/0001")).andReturn().getResponse();
        assertEquals(503, response.getStatus());
        JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertEquals(2, body.size());
        assertEquals("Market data unavailable", body.get("error").asText());
        assertFalse(response.getContentAsString().contains("synthetic"));
    }

    private void fresh() { f.circuit().reset(); f = new Fixture(id, mode); }
    private void probeTime() { clock.advance(30); clock.tick(); }
    private Object otherQuery(String operation) {
        if (operation.equals("period")) return f.period.getPeriodChart(new PeriodChartRequest("005930", "D", END.minusDays(100), END));
        if (operation.equals("daily")) return f.minute.getDailyMinutes(new MinuteChartRequest("005930", END));
        return f.minute.getRecentMinutes("005930");
    }
    private void assertEveryPathBlocked() {
        assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
        f.server.reset();
        int reads = f.tokenReads;
        int calls = f.httpCalls;
        Map<String, Long> budget = f.budgets();
        assertThrows(MarketDataUnavailableException.class, () -> f.chart.getMarketIndexChart(request("0001")));
        assertThrows(MarketDataUnavailableException.class, () -> f.current.getCurrentPrice("005930"));
        for (String operation : List.of("period", "daily", "recent")) {
            assertThrows(MarketDataUnavailableException.class, () -> otherQuery(operation));
        }
        assertEquals(reads, f.tokenReads);
        assertEquals(calls, f.httpCalls);
        assertEquals(budget, f.budgets());
        assertEquals(0, f.redis.writes);
        f.server.verify();
    }
    private static MarketIndexChartRequest request(String index) {
        return new MarketIndexChartRequest(index, END);
    }
    private static List<MarketIndexCandle> candles() {
        return List.of(new MarketIndexCandle("20261023", "2500.50", "2400.00", "2600.00", "2300.00"));
    }

    private class Fixture {
        final Provider provider;
        final String base;
        final MemoryRedis redis = new MemoryRedis();
        final AtomicLong nanos = new AtomicLong();
        final MockRestServiceServer server;
        final CurrentPriceProvider current;
        final MarketIndexChartProvider chart;
        final PeriodChartProvider period;
        final MinuteChartProvider minute;
        RateLimitedKisApiClient kisLimiter;
        KiwoomRestRateLimiter kiwoomLimiter;
        KiwoomTokenService kiwoomTokens;
        int tokenReads;
        int httpCalls;
        boolean failToken;

        Fixture(Provider provider, String environment) {
            this.provider = provider;
            RestTemplate rest = new RestTemplate();
            server = MockRestServiceServer.bindTo(rest).build();
            if (provider == Provider.KIS) {
                KisConfig config = new KisConfig();
                config.mode = environment;
                config.appKey = "synthetic-key"; config.appSecret = "synthetic-secret";
                config.accountNo = "synthetic-account"; config.acntPrdtCd = "01";
                base = config.getBaseUrl();
                kisLimiter = new RateLimitedKisApiClient(redis, config, new KisRestRateLimitConfig(18, 1));
                setKisBudget(budget(1000));
                KisAccessTokenRedisRepository tokens = new KisAccessTokenRedisRepository(redis) {
                    @Override public Optional<String> findAccessToken(KisAccessTokenScope scope) { return Optional.of(token()); }
                };
                KisApiService api = new KisApiService(config, null, rest, tokens,
                        new KisChartRedisRepository(redis, new ObjectMapper()),
                        new KisMarketIndexChartRedisRepository(redis, new ObjectMapper()), null, null, null, kisLimiter, breakers);
                current = new KisCurrentPriceProvider(api);
                chart = new KisMarketIndexChartProvider(api);
                period = new KisPeriodChartProvider(api);
                minute = new KisMinuteChartProvider(api);
            } else {
                KiwoomAccessTokenScope scope = new KiwoomAccessTokenScope(environment, "synthetic-key", "synthetic-secret");
                base = scope.getBaseUrl();
                kiwoomTokens = new KiwoomTokenService(scope, 600, rest, null, null, clock) {
                    @Override public String getAccessToken() { return token(); }
                };
                kiwoomLimiter = KiwoomRestRateLimitTest.withClock(new TimeMeter() {
                    @Override public long currentTimeNanos() { return nanos.get(); }
                    @Override public boolean isWallClockBased() { return false; }
                });
                current = new KiwoomCurrentPriceProvider(kiwoomTokens, rest, kiwoomLimiter,
                        new KiwoomCurrentPriceRedisRepository(redis, clock), breakers);
                chart = new KiwoomMarketIndexChartProvider(kiwoomTokens, rest, kiwoomLimiter,
                        new KiwoomMarketIndexChartRedisRepository(redis, clock), breakers);
                period = new KiwoomPeriodChartProvider(kiwoomTokens, rest, kiwoomLimiter,
                        new KiwoomPeriodChartRedisRepository(redis, clock), breakers);
                minute = new KiwoomMinuteChartProvider(kiwoomTokens, rest, kiwoomLimiter,
                        new KiwoomDailyMinuteRedisRepository(redis, clock), breakers, QUERY_CLOCK);
            }
        }

        CircuitBreaker circuit() { return breakers.circuit(provider, base); }
        String token() {
            tokenReads++;
            if (failToken) throw new IllegalStateException("synthetic-sensitive-token-store");
            return "synthetic-token";
        }
        void open() { currentFailure(); currentFailure(); }
        void currentFailure() {
            respond(withServerError());
            assertClassified(assertThrows(RuntimeException.class, () -> current.getCurrentPrice("005930")));
            server.verify();
        }
        void chartFailure() {
            respond(withServerError());
            assertClassified(assertThrows(RuntimeException.class, () -> chart.getMarketIndexChart(request("0001"))));
            server.verify();
        }
        void assertClassified(RuntimeException error) {
            assertTrue(error instanceof KisMarketDataException || error instanceof KiwoomMarketDataException);
        }
        void respond(ResponseCreator response) {
            nanos.addAndGet(1_000_000_000L);
            server.reset();
            server.expect(request -> {
                httpCalls++;
                assertTrue(request.getURI().toString().startsWith(base));
                assertEquals("Bearer synthetic-token", request.getHeaders().getFirst("Authorization"));
            }).andRespond(response);
        }
        ResponseCreator currentSuccess() {
            return withSuccess(provider == Provider.KIS ? "{\"output\":{\"stck_prpr\":\"71000\"}}"
                    : "{\"cur_prc\":\"+71000\"}", MediaType.APPLICATION_JSON);
        }
        ResponseCreator otherSuccess(String operation) {
            String field = provider == Provider.KIS ? "output2"
                    : operation.equals("period") ? "stk_dt_pole_chart_qry" : "stk_min_pole_chart_qry";
            return withSuccess("{\"" + field + "\":[]}", MediaType.APPLICATION_JSON);
        }
        ResponseCreator chartSuccess(String index) {
            return withSuccess(provider == Provider.KIS
                    ? "{\"output2\":[{\"stck_bsop_date\":\"20261023\",\"bstp_nmix_prpr\":\"2500.50\",\"bstp_nmix_oprc\":\"2400.00\",\"bstp_nmix_hgpr\":\"2600.00\",\"bstp_nmix_lwpr\":\"2300.00\"}]}"
                    : "{\"inds_cd\":\"" + (index.equals("0001") ? "001" : "101")
                    + "\",\"inds_dt_pole_qry\":[{\"dt\":\"20261023\",\"cur_prc\":\"+250050\",\"open_pric\":\"240000\",\"high_pric\":\"260000\",\"low_pric\":\"230000\"}]}", MediaType.APPLICATION_JSON);
        }
        void exhaustChartBudget() {
            if (provider == Provider.KIS) setKisBudget(budget(0));
            else {
                nanos.addAndGet(1_000_000_000L);
                int count = base.contains("mockapi") ? 1 : 5;
                for (int i = 0; i < count; i++) kiwoomLimiter.acquireMarketIndexChart(kiwoomTokens);
            }
        }
        void assertLimited() {
            if (provider == Provider.KIS) assertThrows(RateLimitExceededException.class, () -> chart.getMarketIndexChart(request("0001")));
            else assertThrows(KiwoomRateLimitExceededException.class, () -> chart.getMarketIndexChart(request("0001")));
        }
        void restoreBudget() {
            if (provider == Provider.KIS) setKisBudget(budget(1000));
            else nanos.addAndGet(1_000_000_000L);
        }
        @SuppressWarnings("unchecked")
        Map<String, Long> budgets() {
            Map<String, Bucket> buckets = (Map<String, Bucket>) ReflectionTestUtils.getField(
                    provider == Provider.KIS ? kisLimiter : kiwoomLimiter, "buckets");
            Map<String, Long> values = new HashMap<>();
            buckets.forEach((key, bucket) -> values.put(key, bucket.getAvailableTokens()));
            return values;
        }
        @SuppressWarnings("unchecked")
        void setKisBudget(Bucket budget) {
            ((Map<String, Bucket>) ReflectionTestUtils.getField(kisLimiter, "buckets")).put(
                    new KisRestRequestScope(base, "synthetic-account", "01", "synthetic-key", "synthetic-secret").getBudgetKey(), budget);
        }
    }

    private static Bucket budget(long count) {
        Bucket bucket = Bucket.builder().addLimit(Bandwidth.builder().capacity(Math.max(1, count))
                .refillIntervally(Math.max(1, count), Duration.ofDays(1)).build()).build();
        if (count == 0) bucket.tryConsume(1);
        return bucket;
    }

    private class MemoryRedis extends RedisTemplate<String, Object> {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        final Map<String, Long> expires = new ConcurrentHashMap<>();
        int writes;
        long lastTtlMillis;
        boolean failReads;
        boolean failWrites;
        @Override @SuppressWarnings("unchecked")
        public ValueOperations<String, Object> opsForValue() {
            return (ValueOperations<String, Object>) Proxy.newProxyInstance(ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class}, (proxy, method, args) -> {
                        if ("get".equals(method.getName())) {
                            if (failReads) throw new IllegalStateException("synthetic-read");
                            if (clock.millis() >= expires.getOrDefault(args[0], Long.MAX_VALUE)) return null;
                            return values.get(args[0]);
                        }
                        if ("set".equals(method.getName()) && args.length == 4) {
                            if (failWrites) throw new IllegalStateException("synthetic-write");
                            writes++;
                            lastTtlMillis = ((TimeUnit) args[3]).toMillis(((Number) args[2]).longValue());
                            expires.put((String) args[0], clock.millis() + lastTtlMillis);
                            values.put((String) args[0], args[1]);
                            return null;
                        }
                        throw new AssertionError(method.getName());
                    });
        }
    }
}
