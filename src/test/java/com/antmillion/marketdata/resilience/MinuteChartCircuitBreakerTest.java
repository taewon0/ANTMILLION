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
import com.antmillion.kis.repository.KisChartRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisCurrentPriceProvider;
import com.antmillion.kis.service.KisPeriodChartProvider;
import com.antmillion.kis.service.KisMinuteChartProvider;
import com.antmillion.kiwoom.repository.KiwoomDailyMinuteRedisRepository;
import com.antmillion.kiwoom.service.KiwoomMinuteChartProvider;
import com.antmillion.marketdata.dto.MinuteCandle;
import com.antmillion.marketdata.dto.MinuteChartRequest;
import com.antmillion.marketdata.dto.RecentMinuteCandle;
import com.antmillion.marketdata.provider.MinuteChartProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomMarketDataException;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomCurrentPriceRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomPeriodChartRedisRepository;
import com.antmillion.kiwoom.service.KiwoomCurrentPriceProvider;
import com.antmillion.kiwoom.service.KiwoomPeriodChartProvider;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomRestRateLimitTest;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.marketdata.config.MarketDataCircuitBreakerConfig;
import com.antmillion.marketdata.dto.PeriodChartRequest;
import com.antmillion.marketdata.exception.MarketDataUnavailableException;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
import com.antmillion.marketdata.provider.PeriodChartProvider;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 실제 양 Provider의 일별·최근 분봉/현재가/기간봉을 외부 I/O 없는 대역으로 검증합니다. */
@RunWith(Parameterized.class)
public class MinuteChartCircuitBreakerTest {
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

    public MinuteChartCircuitBreakerTest(Provider id, String mode) { this.id = id; this.mode = mode; }
    @Before public void setUp() { f = new Fixture(id, mode); }

    @Test
    public void dailyUsesSixtySecondCacheAndRecentAlwaysUsesRemote() {
        f.respond(f.minuteSuccess());
        assertEquals(dailyCandles(), query(f, true));
        f.server.verify();
        assertEquals(60_000, f.redis.ttlMillis);
        int reads = f.tokenReads;
        Map<String, Long> budget = f.budgets();
        f.server.reset();
        assertEquals(dailyCandles(), query(f, true));
        assertEquals(reads, f.tokenReads);
        assertEquals(budget, f.budgets());
        assertEquals(1, f.circuit().getMetrics().getNumberOfSuccessfulCalls());
        f.server.verify();
        for (int i = 0; i < 2; i++) {
            f.respond(f.minuteSuccess());
            assertEquals(recentCandles(), query(f, false));
            f.server.verify();
        }
        assertEquals(3, f.circuit().getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(3, f.httpCalls);
        assertEquals(1, f.redis.writes);
    }

    @Test
    public void currentAndEachMinuteFailureCombineAndOpenBlocksBeforeBudgetOrToken() {
        for (boolean daily : List.of(true, false)) {
            fresh();
            f.currentFailure();
            minuteFailure(daily);
            assertAllBlocked();
        }
    }

    @Test
    public void periodAndEachMinuteFailureCombineAndOpenBlocksBeforeBudgetOrToken() {
        for (boolean daily : List.of(true, false)) {
            fresh();
            f.chartFailure();
            minuteFailure(daily);
            assertAllBlocked();
        }
    }

    @Test
    public void dailyAndRecentFailuresCombineAndBlockCurrentAndPeriod() {
        minuteFailure(true); minuteFailure(false);
        assertAllBlocked();
    }

    @Test
    public void currentRecoveryRestoresBothMinutePaths() {
        minuteFailure(true); minuteFailure(false);
        probeTime();
        f.respond(f.currentSuccess());
        assertEquals(Integer.valueOf(71000), f.current.getCurrentPrice("005930"));
        f.server.verify();
        assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
        for (boolean daily : List.of(true, false)) {
            f.respond(f.minuteSuccess());
            assertEquals(expected(daily), query(f, daily));
            f.server.verify();
        }
    }

    @Test
    public void eitherMinuteProbeRestoresCurrentAndPeriod() {
        for (boolean daily : List.of(true, false)) {
            fresh(); f.open();
            clock.advance(30);
            assertThrows(MarketDataUnavailableException.class, () -> query(f, daily));
            clock.tick();
            f.respond(f.minuteSuccess());
            assertEquals(expected(daily), query(f, daily));
            f.server.verify();
            assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
            f.respond(f.currentSuccess());
            assertEquals(Integer.valueOf(71000), f.current.getCurrentPrice("005930"));
            f.server.verify();
            f.respond(f.chartSuccess("D"));
            assertEquals(1, f.chart.getPeriodChart(request("D")).size());
            f.server.verify();
        }
    }

    @Test
    public void cachedDailyDuringOpenAndHalfOpenNeverConsumesPermitOrRecovers() {
        f.respond(f.minuteSuccess()); query(f, true); f.server.verify();
        f.circuit().reset(); f.open(); f.server.reset();
        int reads = f.tokenReads;
        Map<String, Long> budget = f.budgets();
        assertEquals(dailyCandles(), query(f, true));
        assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
        probeTime();
        assertEquals(dailyCandles(), query(f, true));
        assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
        try (MarketDataCircuitBreakers.Call pending = breakers.acquire(id, f.base)) {
            assertEquals(dailyCandles(), query(f, true));
            assertEquals(CircuitBreaker.State.HALF_OPEN, f.circuit().getState());
            assertThrows(MarketDataUnavailableException.class, () -> query(f, false));
            assertThrows(MarketDataUnavailableException.class, () -> f.minute.getDailyMinutes(
                    new MinuteChartRequest("000660", END)));
            assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
        }
        assertEquals(reads, f.tokenReads);
        assertEquals(budget, f.budgets());
        f.server.verify();
        f.respond(f.minuteSuccess());
        assertEquals(recentCandles(), query(f, false));
        assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
        f.server.verify();
    }

    @Test
    public void eitherMinuteProbeFailureReopensWithoutCaching() {
        for (boolean daily : List.of(true, false)) {
            fresh(); f.open(); probeTime(); minuteFailure(daily);
            assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
            assertEquals(0, f.redis.writes);
            assertThrows(MarketDataUnavailableException.class, () -> query(f, !daily));
        }
    }

    @Test
    public void otherProviderAndEnvironmentRemainCallable() {
        minuteFailure(true); minuteFailure(false);
        for (Fixture other : List.of(new Fixture(id, mode.equals("real") ? "virtual" : "real"),
                new Fixture(id == Provider.KIS ? Provider.KIWOOM : Provider.KIS, mode))) {
            for (boolean daily : List.of(true, false)) {
                other.respond(other.minuteSuccess());
                assertEquals(expected(daily), query(other, daily));
                other.server.verify();
            }
            assertEquals(CircuitBreaker.State.CLOSED, other.circuit().getState());
            assertEquals(2, other.circuit().getMetrics().getNumberOfSuccessfulCalls());
        }
        assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
    }

    @Test
    public void authenticationRateBusinessAndResponseErrorsNeverEnterDenominator() {
        for (boolean daily : List.of(true, false)) {
            fresh();
            List<ResponseCreator> ignored = new ArrayList<>();
            for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
                    HttpStatus.TOO_MANY_REQUESTS, HttpStatus.BAD_REQUEST)) ignored.add(withStatus(status));
            ignored.add(withSuccess("broken-json", MediaType.APPLICATION_JSON));
            ignored.add(withSuccess(id == Provider.KIS ? "{\"rt_cd\":\"9\"}" : "{\"return_code\":9}", MediaType.APPLICATION_JSON));
            if (id == Provider.KIWOOM) ignored.add(withSuccess(
                    "{\"stk_min_pole_chart_qry\":[{\"cntr_tm\":\"20261023145900\",\"cur_prc\":\"bad\"}]}", MediaType.APPLICATION_JSON));
            for (ResponseCreator response : ignored) {
                f.respond(response);
                RuntimeException error = assertThrows(RuntimeException.class, () -> query(f, daily));
                f.assertClassified(error);
                f.server.verify();
                assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
            }
            assertEquals(0, f.redis.writes);
        }
    }

    @Test
    public void transportFailuresCountAndNeverRetainSensitiveCause() {
        for (boolean daily : List.of(true, false)) {
            fresh();
            for (int i = 0; i < 2; i++) {
                f.respond(request -> { throw new IOException("synthetic-sensitive-cause"); });
                RuntimeException error = assertThrows(RuntimeException.class, () -> query(f, daily));
                assertNull(error.getCause());
                assertFalse(error.toString().contains("synthetic-sensitive"));
                f.server.verify();
            }
            assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
        }
    }

    @Test
    public void localRateLimitExcludesSampleAndReturnsHalfOpenPermit() {
        for (boolean daily : List.of(true, false)) {
            fresh(); f.exhaustMinuteBudget(); f.assertLimited(daily);
            assertEquals(0, f.tokenReads);
            assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
            f.restoreBudget(); f.open(); probeTime();
            int reads = f.tokenReads;
            f.exhaustMinuteBudget(); f.assertLimited(daily);
            assertEquals(reads, f.tokenReads);
            assertEquals(CircuitBreaker.State.HALF_OPEN, f.circuit().getState());
            assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
            f.restoreBudget(); f.respond(f.minuteSuccess());
            assertEquals(expected(daily), query(f, daily));
            assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
            f.server.verify();
        }
    }

    @Test
    public void tokenFailureReturnsHalfOpenPermitWithoutFalseRecovery() {
        for (boolean daily : List.of(true, false)) {
            fresh(); f.open(); probeTime(); f.failToken = true;
            f.nanos.addAndGet(1_000_000_000L); f.server.reset();
            assertThrows(RuntimeException.class, () -> query(f, daily));
            assertEquals(CircuitBreaker.State.HALF_OPEN, f.circuit().getState());
            assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
            f.server.verify();
            f.failToken = false; f.respond(f.minuteSuccess());
            assertEquals(expected(daily), query(f, daily));
            assertEquals(CircuitBreaker.State.CLOSED, f.circuit().getState());
            f.server.verify();
        }
    }

    @Test
    public void dailyCacheReadFailureRetainsExistingPolicyWithoutFalseSamples() {
        f.redis.failReads = true;
        if (id == Provider.KIS) {
            assertThrows(IllegalStateException.class, () -> query(f, true));
            assertEquals(0, f.tokenReads);
            assertEquals(0, f.circuit().getMetrics().getNumberOfBufferedCalls());
            f.server.verify();
        } else {
            f.respond(f.minuteSuccess());
            assertEquals(dailyCandles(), query(f, true));
            assertEquals(1, f.circuit().getMetrics().getNumberOfSuccessfulCalls());
            f.server.verify();
        }
    }

    @Test
    public void dailyCacheWriteFailureKeepsRemoteSampleAndExistingProviderPolicy() {
        f.redis.failWrites = true; f.respond(f.minuteSuccess());
        if (id == Provider.KIS) assertThrows(IllegalStateException.class, () -> query(f, true));
        else assertEquals(dailyCandles(), query(f, true));
        assertEquals(1, f.circuit().getMetrics().getNumberOfSuccessfulCalls());
        assertEquals(0, f.circuit().getMetrics().getNumberOfFailedCalls());
        f.server.verify();
    }

    @Test
    public void emptyAndMissingListsPreserveExistingResponsesAndCachePolicy() {
        for (boolean missing : List.of(false, true)) {
            fresh();
            for (boolean daily : List.of(true, false)) {
                String field = id == Provider.KIS ? "output2" : "stk_min_pole_chart_qry";
                f.respond(withSuccess(missing ? "{}" : "{\"" + field + "\":[]}", MediaType.APPLICATION_JSON));
                Object result = query(f, daily);
                if (missing && !daily) assertNull(result);
                else assertEquals(Collections.emptyList(), result);
                f.server.verify();
            }
            assertEquals(2, f.circuit().getMetrics().getNumberOfSuccessfulCalls());
            assertEquals(missing ? 0 : 1, f.redis.writes);
        }
    }

    @Test
    public void existingMinuteHttpPathsReturnSafe503WhenOpen() throws Exception {
        f.open(); f.server.reset();
        for (String path : List.of("/api/kis/stream/005930", "/api/kis/candle/005930")) {
            org.springframework.mock.web.MockHttpServletResponse response = MockMvcBuilders
                    .standaloneSetup(new KisController(null, null, null, f.minute, null, null))
                    .setControllerAdvice(new GlobalExceptionHandler()).build()
                    .perform(get(path)).andReturn().getResponse();
            assertEquals(503, response.getStatus());
            JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
            assertEquals(2, body.size());
            assertEquals("Market data unavailable", body.get("error").asText());
            assertFalse(response.getContentAsString().contains("synthetic"));
        }
        f.server.verify();
    }

    private void fresh() { f.circuit().reset(); f = new Fixture(id, mode); }
    private void probeTime() { clock.advance(30); clock.tick(); }
    private void minuteFailure(boolean daily) {
        f.respond(withServerError());
        f.assertClassified(assertThrows(RuntimeException.class, () -> query(f, daily)));
        f.server.verify();
    }
    private void assertAllBlocked() {
        assertEquals(CircuitBreaker.State.OPEN, f.circuit().getState());
        f.server.reset();
        int reads = f.tokenReads;
        int calls = f.httpCalls;
        Map<String, Long> budget = f.budgets();
        for (boolean daily : List.of(true, false)) assertThrows(MarketDataUnavailableException.class, () -> query(f, daily));
        assertThrows(MarketDataUnavailableException.class, () -> f.current.getCurrentPrice("005930"));
        assertThrows(MarketDataUnavailableException.class, () -> f.chart.getPeriodChart(request("D")));
        assertEquals(reads, f.tokenReads);
        assertEquals(calls, f.httpCalls);
        assertEquals(budget, f.budgets());
        assertEquals(0, f.redis.writes);
        f.server.verify();
    }
    private Object query(Fixture fixture, boolean daily) {
        return daily ? fixture.minute.getDailyMinutes(new MinuteChartRequest("005930", END))
                : fixture.minute.getRecentMinutes("005930");
    }
    private static Object expected(boolean daily) { return daily ? dailyCandles() : recentCandles(); }
    private static List<MinuteCandle> dailyCandles() {
        return List.of(new MinuteCandle("20261023", "145900", "71000", "70000", "72000", "69000"));
    }
    private static List<RecentMinuteCandle> recentCandles() {
        return List.of(new RecentMinuteCandle("145900", 71000, 70000, 72000, 69000));
    }
    private static PeriodChartRequest request(String period) {
        return new PeriodChartRequest("005930", period, END.minusYears(30), END);
    }

    private class Fixture {
        final Provider provider;
        final String base;
        final MemoryRedis redis = new MemoryRedis();
        final AtomicLong nanos = new AtomicLong();
        final MockRestServiceServer server;
        final CurrentPriceProvider current;
        final PeriodChartProvider chart;
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
                        new KisChartRedisRepository(redis, new ObjectMapper()), null, null, null, null, kisLimiter, breakers);
                current = new KisCurrentPriceProvider(api);
                chart = new KisPeriodChartProvider(api);
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
                chart = new KiwoomPeriodChartProvider(kiwoomTokens, rest, kiwoomLimiter,
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
            assertClassified(assertThrows(RuntimeException.class, () -> chart.getPeriodChart(request("D"))));
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
        ResponseCreator chartSuccess(String period) {
            String field = Map.of("D", "stk_dt_pole_chart_qry", "W", "stk_stk_pole_chart_qry",
                    "M", "stk_mth_pole_chart_qry", "Y", "stk_yr_pole_chart_qry").get(period);
            return withSuccess(provider == Provider.KIS
                    ? "{\"output2\":[{\"stck_bsop_date\":\"20261023\",\"stck_clpr\":\"71000\",\"stck_oprc\":\"70000\",\"stck_hgpr\":\"72000\",\"stck_lwpr\":\"69000\"}]}"
                    : "{\"" + field + "\":[{\"dt\":\"20261023\",\"cur_prc\":\"+71000\",\"open_pric\":\"70000\",\"high_pric\":\"72000\",\"low_pric\":\"69000\"}]}", MediaType.APPLICATION_JSON);
        }
        void exhaustMinuteBudget() {
            if (provider == Provider.KIS) setKisBudget(budget(0));
            else {
                nanos.addAndGet(1_000_000_000L);
                int count = base.contains("mockapi") ? 1 : 5;
                for (int i = 0; i < count; i++) kiwoomLimiter.acquireMinuteChart(kiwoomTokens);
            }
        }
        void assertLimited(boolean daily) {
            if (provider == Provider.KIS) assertThrows(RateLimitExceededException.class, () -> query(this, daily));
            else assertThrows(KiwoomRateLimitExceededException.class, () -> query(this, daily));
        }
        ResponseCreator minuteSuccess() {
            return withSuccess(provider == Provider.KIS
                    ? "{\"output2\":[{\"stck_bsop_date\":\"20261023\",\"stck_cntg_hour\":\"145900\",\"stck_prpr\":\"71000\",\"stck_oprc\":\"70000\",\"stck_hgpr\":\"72000\",\"stck_lwpr\":\"69000\"}]}"
                    : "{\"stk_min_pole_chart_qry\":[{\"cntr_tm\":\"20261023145900\",\"cur_prc\":\"+71000\",\"open_pric\":\"70000\",\"high_pric\":\"72000\",\"low_pric\":\"69000\"}]}", MediaType.APPLICATION_JSON);
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

    private static class MemoryRedis extends RedisTemplate<String, Object> {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        int writes;
        long ttlMillis;
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
                            writes++;
                            ttlMillis = ((java.util.concurrent.TimeUnit) args[3]).toMillis(((Number) args[2]).longValue());
                            values.put((String) args[0], args[1]);
                            return null;
                        }
                        throw new AssertionError(method.getName());
                    });
        }
    }
}
