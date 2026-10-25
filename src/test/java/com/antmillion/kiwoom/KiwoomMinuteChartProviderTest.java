package com.antmillion.kiwoom;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisChartRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisMinuteChartProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomDailyMinuteRedisRepository;
import com.antmillion.kiwoom.service.KiwoomMinuteChartProvider;
import com.antmillion.kiwoom.service.KiwoomRestRateLimitTest;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.marketdata.dto.MinuteCandle;
import com.antmillion.marketdata.dto.MinuteChartRequest;
import com.antmillion.marketdata.dto.RecentMinuteCandle;
import com.antmillion.marketdata.provider.MinuteChartProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.TimeMeter;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 고정 시각·합성 인증·메모리 Redis/잠금·HTTP 대역만 사용하며 실제 설정/DB는 읽지 않습니다. */
public class KiwoomMinuteChartProviderTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 23);
    private static final Clock QUERY_CLOCK = Clock.fixed(Instant.parse("2026-10-23T06:00:00Z"),
            ZoneId.of("Asia/Seoul"));
    private final AtomicLong nanos = new AtomicLong();
    private boolean advanceTime = true;
    private KiwoomTokenServiceTest.MutableClock tokenClock;
    private KiwoomTokenServiceTest.MemoryRedis redis;
    private KiwoomTokenServiceTest.LockClient locks;
    private KiwoomAccessTokenRedisRepository repository;
    private KiwoomAccessTokenScope scope;
    private KiwoomRestRateLimiter limiter;
    private RestTemplate rest;
    private MockRestServiceServer server;
    private MinuteChartProvider provider;

    @Before
    public void setUp() {
        tokenClock = new KiwoomTokenServiceTest.MutableClock();
        redis = new KiwoomTokenServiceTest.MemoryRedis(tokenClock);
        locks = new KiwoomTokenServiceTest.LockClient();
        repository = new KiwoomAccessTokenRedisRepository(redis, tokenClock);
        scope = new KiwoomAccessTokenScope("virtual", "synthetic-key", "synthetic-secret");
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        limiter = KiwoomRestRateLimitTest.withClock(new TimeMeter() {
            @Override public long currentTimeNanos() {
                return advanceTime ? nanos.getAndAdd(1_000_000_000L) : nanos.get();
            }
            @Override public boolean isWallClockBased() { return false; }
        });
        cacheToken(scope);
        provider = provider(scope, QUERY_CLOCK);
    }

    @Test
    public void dailyUsesKrxOneMinuteAdjustedDateAndReturnsAscendingStringPrices() {
        expect(scope, "20261023").andRespond(withSuccess(body(
                row("20261023153000", "-071000.00"), row("20261023152900", "+70000")), MediaType.APPLICATION_JSON));
        assertEquals(List.of(candle("152900", "70000"), candle("153000", "71000")),
                provider.getDailyMinutes(request()));
        server.verify();
    }

    @Test
    public void recentUsesCurrentDateAndReturnsLatestFirstLongPrices() {
        expect(scope, "20261023").andRespond(withSuccess(body(
                row("20261023150000", "-71000.00"), row("20261023145900", "+70000")), MediaType.APPLICATION_JSON));
        assertEquals(List.of(recent("150000", 71000), recent("145900", 70000)),
                provider.getRecentMinutes("005930"));
        server.verify();
    }

    @Test
    public void dailyKeepsEarlierDatesButFiltersFutureDatesAndTimesAfter1530() {
        expect(scope, "20261023").andRespond(withSuccess(body(row("20261024100000", "1"),
                row("20261023153100", "2"), row("20261023153000", "3"),
                row("20261022153100", "5"), row("20261022153000", "4")), MediaType.APPLICATION_JSON));
        assertEquals(List.of(new MinuteCandle("20261022", "153000", "4", "100", "200", "50"),
                candle("153000", "3")), provider.getDailyMinutes(request()));
        server.verify();
    }

    @Test
    public void holidayDailyKeepsActualDatesWithoutRetryOrTradingDaySelection() {
        for (LocalDate date : List.of(DATE.plusDays(1), DATE.plusDays(3), DATE.plusDays(7))) {
            server.reset();
            expect(scope, date.format(DateTimeFormatter.BASIC_ISO_DATE)).andRespond(withSuccess(body(
                    row("20261023153000", "-071000.00"), row("20261022153000", "+70000")),
                    MediaType.APPLICATION_JSON).headers(continuation()));
            List<MinuteCandle> result = provider.getDailyMinutes(new MinuteChartRequest("005930", date));
            assertEquals(List.of(new MinuteCandle("20261022", "153000", "70000", "100", "200", "50"),
                    candle("153000", "71000")), result);
            server.verify();
        }
    }

    @Test
    public void earlierDatesStillRequireValidDescendingTimestamps() {
        for (String[] rows : List.of(new String[]{row("20261022150000", "1"), row("20261022150100", "2")},
                new String[]{row("20261022150000", "1"), row("20261023140000", "2")},
                new String[]{row("20260230150000", "1")}, new String[]{row("00001022150000", "1")})) {
            server.reset();
            expect(scope, "20261023").andRespond(withSuccess(body(rows), MediaType.APPLICATION_JSON));
            assertSanitized(() -> provider.getDailyMinutes(request()));
            server.verify();
        }
    }

    @Test
    public void recentExcludesFutureCandlesAndPreviousTradingDate() {
        expect(scope, "20261023").andRespond(withSuccess(body(row("20261023150100", "1"),
                row("20261023150000", "2"), row("20261022150000", "3")), MediaType.APPLICATION_JSON));
        assertEquals(List.of(recent("150000", 2)), provider.getRecentMinutes("005930"));
        server.verify();
    }

    @Test
    public void holidayRecentStillExcludesPreviousTradingDateWithoutRetry() {
        Clock holiday = Clock.fixed(Instant.parse("2026-10-24T13:21:00Z"), ZoneId.of("Asia/Seoul"));
        expect(scope, "20261024").andRespond(withSuccess(body(row("20261023153000", "71000")),
                MediaType.APPLICATION_JSON).headers(continuation()));
        assertTrue(provider(scope, holiday).getRecentMinutes("005930").isEmpty());
        server.verify();
    }

    @Test
    public void dailySupportsHistoricalBaseDateWithoutAccumulatingPages() {
        expect(scope, "20251023").andRespond(withSuccess(body(row("20251023153000", "100")),
                MediaType.APPLICATION_JSON).headers(continuation()));
        assertEquals(List.of(new MinuteCandle("20251023", "153000", "100", "100", "200", "50")),
                provider.getDailyMinutes(new MinuteChartRequest("005930", DATE.minusYears(1))));
        server.verify();
    }

    @Test
    public void dailyHonors119120121BoundariesAndReversesAfterTruncation() {
        for (LocalDate date : List.of(DATE, DATE.minusDays(1))) {
            for (int size : List.of(119, 120, 121)) {
                server.reset();
                List<String> rows = new ArrayList<>();
                LocalDateTime end = date.atTime(15, 30);
                for (int i = 0; i < size; i++) rows.add(row(stamp(end.minusMinutes(i)), Integer.toString(i)));
                expect(scope, "20261023").andRespond(withSuccess(body(rows.toArray(new String[0])),
                        MediaType.APPLICATION_JSON).headers(continuation()));
                List<MinuteCandle> result = provider.getDailyMinutes(request());
                int count = Math.min(size, 120);
                assertEquals(count, result.size());
                assertEquals(Integer.toString(count - 1), result.get(0).getClosePrice());
                assertEquals("0", result.get(count - 1).getClosePrice());
                assertEquals(date.format(DateTimeFormatter.BASIC_ISO_DATE), result.get(count - 1).getDate());
                server.verify();
            }
        }
    }

    @Test
    public void recentHonors293031BoundariesAndKeepsLatestFirst() {
        for (int size : List.of(29, 30, 31)) {
            server.reset();
            List<String> rows = new ArrayList<>();
            LocalDateTime end = DATE.atTime(15, 0);
            for (int i = 0; i < size; i++) rows.add(row(stamp(end.minusMinutes(i)), Integer.toString(i)));
            expect(scope, "20261023").andRespond(withSuccess(body(rows.toArray(new String[0])),
                    MediaType.APPLICATION_JSON).headers(continuation()));
            List<RecentMinuteCandle> result = provider.getRecentMinutes("005930");
            assertEquals(Math.min(size, 30), result.size());
            assertEquals(0, result.get(0).getClosePrice());
            assertEquals(Math.min(size, 30) - 1, result.get(result.size() - 1).getClosePrice());
            server.verify();
        }
    }

    @Test
    public void emptyAndMissingListsKeepExistingDailyAndRecentContracts() {
        for (String json : List.of(body(), "{\"return_code\":0}", "{\"stk_min_pole_chart_qry\":null}")) {
            server.reset();
            expect(scope, "20261023").andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            expect(scope, "20261023").andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertTrue(provider.getDailyMinutes(request()).isEmpty());
            assertEquals(json.equals(body()) ? List.of() : null, provider.getRecentMinutes("005930"));
            server.verify();
        }
    }

    @Test
    public void dailyKeepsDuplicatesNullRowsMissingAndEmptyPrices() {
        expect(scope, "20261023").andRespond(withSuccess(body(row("20261023150000", "10"),
                row("20261023150000", "10"), "{\"cntr_tm\":\"20261023145900\",\"cur_prc\":\"\"}", "null"),
                MediaType.APPLICATION_JSON));
        assertEquals(Arrays.asList(null, new MinuteCandle("20261023", "145900", "", null, null, null),
                candle("150000", "10"), candle("150000", "10")), provider.getDailyMinutes(request()));
    }

    @Test
    public void recentMapsMissingOrEmptyPricesToZeroAndPreservesNullRows() {
        expect(scope, "20261023").andRespond(withSuccess(body("{\"cntr_tm\":\"20261023150000\","
                + "\"cur_prc\":\"\",\"high_pric\":null}", "null"), MediaType.APPLICATION_JSON));
        assertEquals(Arrays.asList(new RecentMinuteCandle("150000", 0, 0, 0, 0), null),
                provider.getRecentMinutes("005930"));
    }

    @Test
    public void dailySupportsLargeIntegerPricesWhileRecentRejectsLongOverflow() {
        String json = body(row("20261023150000", "9223372036854775808"));
        expect(scope, "20261023").andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        expect(scope, "20261023").andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        assertEquals("9223372036854775808", provider.getDailyMinutes(request()).get(0).getClosePrice());
        assertSanitized(() -> provider.getRecentMinutes("005930"));
    }

    @Test
    public void invalidInputDoesNotConsumeBudgetOrReadToken() {
        for (String code : Arrays.asList(null, "", "005930_NX", "005930_AL", "bad", "005930 ")) {
            assertThrows(IllegalArgumentException.class, () -> provider.getDailyMinutes(new MinuteChartRequest(code, DATE)));
            assertThrows(IllegalArgumentException.class, () -> provider.getRecentMinutes(code));
        }
        for (MinuteChartRequest query : Arrays.asList(null, new MinuteChartRequest("005930", null),
                new MinuteChartRequest("005930", LocalDate.of(0, 1, 1)),
                new MinuteChartRequest("005930", LocalDate.of(10000, 1, 1)))) {
            assertThrows(IllegalArgumentException.class, () -> provider.getDailyMinutes(query));
        }
        assertTrue(redis.reads.isEmpty());
        advanceTime = false;
        expect(scope, "20261023").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        assertTrue(provider.getDailyMinutes(request()).isEmpty());
        server.verify();
    }

    @Test
    public void malformedTimestampsAndAscendingResponsesFailWithoutPartialResults() {
        for (String time : Arrays.asList(null, "", "20261023", "20260230150000", "20261023240000", "00001023150000")) {
            server.reset();
            String bad = time == null ? "{}" : "{\"cntr_tm\":\"" + time + "\"}";
            expect(scope, "20261023").andRespond(withSuccess(body(row("20261023150000", "1"), bad), MediaType.APPLICATION_JSON));
            assertSanitized(() -> provider.getDailyMinutes(request()));
            server.verify();
        }
        server.reset();
        expect(scope, "20261023").andRespond(withSuccess(body(row("20261023145900", "1"),
                row("20261023150000", "2")), MediaType.APPLICATION_JSON));
        assertSanitized(() -> provider.getRecentMinutes("005930"));
    }

    @Test
    public void fractionalMalformedAndExponentPricesFailWithoutLeakingValues() {
        for (String value : List.of("1.1", "1e3", "NaN", "synthetic-sensitive", "1,000")) {
            server.reset();
            expect(scope, "20261023").andRespond(withSuccess(body(row("20261023150000", value)), MediaType.APPLICATION_JSON));
            assertSanitized(() -> provider.getDailyMinutes(request()));
            server.verify();
        }
    }

    @Test
    public void badBodiesBusinessErrorsAndDifferentStockFailSanitized() {
        for (String json : List.of("", "null", "{broken", "{\"return_code\":1,\"return_msg\":\"synthetic-sensitive\"}",
                body().replace("005930", "000660"), "{\"stk_min_pole_chart_qry\":{}}")) {
            server.reset();
            expect(scope, "20261023").andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertSanitized(() -> provider.getDailyMinutes(request()));
            server.verify();
        }
    }

    @Test
    public void remote401429And500FailWithoutFallbackRetryOrBodyLeak() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR)) {
            server.reset();
            expect(scope, "20261023").andRespond(withStatus(status).body("synthetic-sensitive"));
            assertSanitized(() -> provider.getRecentMinutes("005930"));
            server.verify();
        }
    }

    @Test
    public void transportFailureIsSanitized() {
        expect(scope, "20261023").andRespond(http -> { throw new IOException("synthetic-sensitive"); });
        assertSanitized(() -> provider.getDailyMinutes(request()));
        server.verify();
    }

    @Test
    public void bothMinuteMethodsShareVirtualBudgetAndRejectBeforeTokenRead() {
        advanceTime = false;
        expect(scope, "20261023").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON).headers(continuation()));
        provider.getDailyMinutes(request());
        int reads = redis.reads.size();
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getRecentMinutes("005930"));
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getDailyMinutes(request()));
        assertEquals(reads, redis.reads.size());
        assertEquals(1, redis.writes.get()); // 합성 토큰 외에는 저장하지 않습니다.
        nanos.set(1_000_000_000L);
        server.reset();
        expect(scope, "20261023").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        provider.getRecentMinutes("005930");
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getDailyMinutes(request()));
        server.verify();
    }

    @Test
    public void realMinuteSharesCurrentAndPeriodBudgetInBothDirections() {
        advanceTime = false;
        KiwoomAccessTokenScope real = new KiwoomAccessTokenScope("real", "real-key", "synthetic-secret");
        cacheToken(real);
        KiwoomTokenService tokenService = tokens(real);
        for (int i = 0; i < 4; i++) limiter.acquireCurrentPrice(tokenService);
        expect(real, "20261023").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        provider(real, QUERY_CLOCK).getDailyMinutes(request());
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquirePeriodChart(tokenService, "ka10081"));
        nanos.set(1_000_000_000L);
        for (int i = 0; i < 5; i++) limiter.acquirePeriodChart(tokenService, "ka10081");
        int reads = redis.reads.size();
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider(real, QUERY_CLOCK).getRecentMinutes("005930"));
        assertEquals(reads, redis.reads.size());
        server.verify();
    }

    @Test
    public void virtualDifferentTrAndEnvironmentDoNotSpendMinuteBudget() {
        advanceTime = false;
        limiter.acquireCurrentPrice(tokens(scope));
        limiter.acquirePeriodChart(tokens(scope), "ka10081");
        expect(scope, "20261023").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        KiwoomAccessTokenScope real = new KiwoomAccessTokenScope("real", "synthetic-key", "synthetic-secret");
        cacheToken(real);
        expect(real, "20261023").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        provider.getDailyMinutes(request());
        provider(real, QUERY_CLOCK).getRecentMinutes("005930");
        server.verify();
    }

    @Test
    public void conversionFailuresStillConsumeBudget() {
        advanceTime = false;
        expect(scope, "20261023").andRespond(withSuccess(body(row("20261023150000", "bad")), MediaType.APPLICATION_JSON));
        assertSanitized(() -> provider.getDailyMinutes(request()));
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getRecentMinutes("005930"));
        server.verify();
    }

    @Test
    public void httpFailuresStillConsumeBudget() {
        advanceTime = false;
        expect(scope, "20261023").andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertSanitized(() -> provider.getRecentMinutes("005930"));
        int reads = redis.reads.size();
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getDailyMinutes(request()));
        assertEquals(reads, redis.reads.size());
        server.verify();
    }

    @Test
    public void tokenIssuanceUsesSameEnvironmentAndFollowsLimit() {
        redis.values.clear();
        server.expect(requestTo(scope.getBaseUrl() + "/oauth2/token"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess("{\"token\":\"synthetic-token\","
                + "\"token_type\":\"bearer\",\"expires_dt\":\"20261024090000\",\"return_code\":0}", MediaType.APPLICATION_JSON));
        expect(scope, "20261023").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        provider.getDailyMinutes(request());
        assertEquals(1, locks.unlocks.get());
        server.verify();
    }

    @Test
    public void tokenFailureIsSanitizedAndDoesNotSendChartHttp() {
        redis.values.clear();
        server.expect(requestTo(scope.getBaseUrl() + "/oauth2/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("synthetic-sensitive"));
        assertSanitized(() -> provider.getRecentMinutes("005930"));
        server.verify();
    }

    @Test
    public void queryDateAndTimeUseOneInjectedClockSnapshotAcrossMidnight() {
        Clock utc = Clock.fixed(Instant.parse("2026-10-22T15:00:00Z"), ZoneOffset.UTC);
        Clock kst = utc.withZone(ZoneId.of("Asia/Seoul"));
        expect(scope, "20261022").andRespond(withSuccess(body(row("20261022150000", "1")), MediaType.APPLICATION_JSON));
        expect(scope, "20261023").andRespond(withSuccess(body(row("20261022150000", "1")), MediaType.APPLICATION_JSON));
        assertEquals(1, provider(scope, utc).getRecentMinutes("005930").size());
        assertTrue(provider(scope, kst).getRecentMinutes("005930").isEmpty());
        server.verify();
    }

    @Test
    public void actualKisAndKiwoomAdaptersProduceSameDailyAndRecentDtoAndMvcJson() throws Exception {
        KisConfig config = new KisConfig() {
            @Override public String getBaseUrl() { return "https://openapi.koreainvestment.com:9443"; }
        };
        config.appKey = "synthetic-key";
        config.appSecret = "synthetic-secret";
        config.accountNo = "synthetic-account";
        config.acntPrdtCd = "01";
        RestTemplate kisRest = new RestTemplate();
        MockRestServiceServer kisServer = MockRestServiceServer.bindTo(kisRest).build();
        KisAccessTokenRedisRepository kisTokens = new KisAccessTokenRedisRepository(redis);
        kisTokens.save(new KisAccessTokenScope(config.getBaseUrl(), config.appKey, config.appSecret), "synthetic-kis-token", 86400);
        MinuteChartProvider kis = new KisMinuteChartProvider(new KisApiService(config, null, kisRest, kisTokens,
                new KisChartRedisRepository(redis, new ObjectMapper()), null, null, null, null,
                new RateLimitedKisApiClient(redis, config, new KisRestRateLimitConfig(18, 1)), com.antmillion.marketdata.CircuitBreakerTestSupport.defaults()));
        for (boolean daily : List.of(true, false)) {
            String path = daily ? "inquire-time-dailychartprice" : "inquire-time-itemchartprice";
            kisServer.expect(http -> {
                assertEquals(HttpMethod.GET, http.getMethod());
                assertTrue(http.getURI().toString().contains(path));
                assertTrue(http.getURI().getQuery().contains("FID_INPUT_ISCD=005930"));
                assertEquals(daily ? "FHKST03010230" : "FHKST03010200", http.getHeaders().getFirst("tr_id"));
            }).andRespond(withSuccess("{\"output2\":[{\"stck_bsop_date\":\"20261022\",\"stck_cntg_hour\":\"150000\","
                    + "\"stck_prpr\":\"71000\",\"stck_oprc\":\"100\",\"stck_hgpr\":\"200\",\"stck_lwpr\":\"50\"},"
                    + "{\"stck_bsop_date\":\"20261022\",\"stck_cntg_hour\":\"145900\",\"stck_prpr\":\"\",\"stck_hgpr\":\"0\"},null]}",
                    MediaType.APPLICATION_JSON));
            String candleDate = daily ? "20261022" : "20261023";
            expect(scope, "20261023").andRespond(withSuccess(body(row(candleDate + "150000", "-071000.00"),
                    "{\"cntr_tm\":\"" + candleDate + "145900\",\"cur_prc\":\"\",\"high_pric\":\"0\"}", "null"), MediaType.APPLICATION_JSON));
            List<MinuteCandle> kisDaily = daily ? kis.getDailyMinutes(request()) : List.of();
            List<MinuteCandle> kiwoomDaily = daily ? provider.getDailyMinutes(request()) : List.of();
            List<RecentMinuteCandle> kisRecent = daily ? List.of() : kis.getRecentMinutes("005930");
            List<RecentMinuteCandle> kiwoomRecent = daily ? List.of() : provider.getRecentMinutes("005930");
            assertEquals(kisDaily, kiwoomDaily);
            assertEquals(kisRecent, kiwoomRecent);
            String url = "/api/kis/" + (daily ? "stream/" : "candle/") + "005930";
            JsonNode kisJson = mvcJson(kisDaily, kisRecent, url);
            JsonNode kiwoomJson = mvcJson(kiwoomDaily, kiwoomRecent, url);
            assertEquals(kisJson, kiwoomJson);
            JsonNode populated = kiwoomJson.get(daily ? 2 : 0);
            assertEquals(daily ? 6 : 5, populated.size());
            assertEquals(daily, populated.get("stck_prpr").isTextual());
            if (daily) assertEquals("20261022", populated.get("stck_bsop_date").asText());
            assertTrue(kiwoomJson.get(daily ? 0 : 2).isNull());
            kisServer.verify();
            server.verify();
            kisServer.reset();
            server.reset();
        }
    }

    @Test
    public void debugLoggingDoesNotExposeTokenCredentialsOrRemoteMessage() {
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(RestTemplate.class);
        org.apache.log4j.Level previous = logger.getLevel();
        java.io.StringWriter output = new java.io.StringWriter();
        org.apache.log4j.WriterAppender appender = new org.apache.log4j.WriterAppender(
                new org.apache.log4j.PatternLayout("%m%n"), output);
        logger.addAppender(appender);
        logger.setLevel(org.apache.log4j.Level.DEBUG);
        try {
            expect(scope, "20261023").andRespond(withSuccess(body().replace("\"return_code\":0",
                    "\"return_code\":0,\"return_msg\":\"synthetic-sensitive\""), MediaType.APPLICATION_JSON));
            provider.getDailyMinutes(request());
            assertTrue(output.toString().contains("Writing ["));
            for (String secret : List.of("synthetic-token", "synthetic-key", "synthetic-secret", "synthetic-sensitive")) {
                assertFalse(output.toString().contains(secret));
            }
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.close();
        }
    }

    private ResponseActions expect(KiwoomAccessTokenScope requestScope, String baseDate) {
        return server.expect(requestTo(requestScope.getBaseUrl() + "/api/dostk/chart"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("api-id", "ka10080"))
                .andExpect(header("Authorization", "Bearer synthetic-token"))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(http -> {
                    assertNull(http.getHeaders().getFirst("cont-yn"));
                    assertNull(http.getHeaders().getFirst("next-key"));
                    JsonNode json = new ObjectMapper().readTree(((MockClientHttpRequest) http).getBodyAsString());
                    assertEquals(4, json.size());
                    assertEquals("005930", json.get("stk_cd").asText());
                    assertEquals("1", json.get("tic_scope").asText());
                    assertEquals("1", json.get("upd_stkpc_tp").asText());
                    assertEquals(baseDate, json.get("base_dt").asText());
                });
    }

    private KiwoomTokenService tokens(KiwoomAccessTokenScope requestScope) {
        return new KiwoomTokenService(requestScope, 600, rest, repository, locks.client(), tokenClock);
    }

    private MinuteChartProvider provider(KiwoomAccessTokenScope requestScope, Clock queryClock) {
        // 기존 계약 회귀는 캐시 저장 불가 대역에서 매번 HTTP/제한/변환 동작을 검증합니다.
        KiwoomTokenServiceTest.MemoryRedis chartRedis = new KiwoomTokenServiceTest.MemoryRedis(tokenClock);
        chartRedis.failWrites = true;
        return new KiwoomMinuteChartProvider(tokens(requestScope), rest, limiter,
                new KiwoomDailyMinuteRedisRepository(chartRedis, tokenClock), queryClock);
    }

    private void cacheToken(KiwoomAccessTokenScope requestScope) {
        repository.save(requestScope, "synthetic-token", tokenClock.instant().plusSeconds(86400), 600);
    }

    private static void assertSanitized(Runnable action) {
        IllegalStateException error = assertThrows(IllegalStateException.class, action::run);
        assertNull(error.getCause());
        for (String secret : List.of("synthetic-sensitive", "synthetic-token", "synthetic-secret")) {
            assertFalse(error.getMessage().contains(secret));
        }
    }

    private static MinuteChartRequest request() { return new MinuteChartRequest("005930", DATE); }
    private static String stamp(LocalDateTime date) { return date.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")); }
    private static String body(String... rows) {
        return "{\"stk_cd\":\"005930\",\"return_code\":0,\"stk_min_pole_chart_qry\":[" + String.join(",", rows) + "]}";
    }
    private static String row(String time, String price) {
        return "{\"cntr_tm\":\"" + time + "\",\"cur_prc\":\"" + price
                + "\",\"open_pric\":\"100\",\"high_pric\":\"200\",\"low_pric\":\"50\"}";
    }
    private static MinuteCandle candle(String time, String price) {
        return new MinuteCandle("20261023", time, price, "100", "200", "50");
    }
    private static RecentMinuteCandle recent(String time, long price) {
        return new RecentMinuteCandle(time, price, 100, 200, 50);
    }
    private static HttpHeaders continuation() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("cont-yn", "Y");
        headers.set("next-key", "unused-key");
        return headers;
    }
    private static JsonNode mvcJson(List<MinuteCandle> daily, List<RecentMinuteCandle> recent, String url) throws Exception {
        MinuteChartProvider values = new MinuteChartProvider() {
            @Override public List<MinuteCandle> getDailyMinutes(MinuteChartRequest ignored) { return daily; }
            @Override public List<RecentMinuteCandle> getRecentMinutes(String ignored) { return recent; }
        };
        byte[] bytes = MockMvcBuilders.standaloneSetup(new KisController(null, null, null, values, null, null))
                .build().perform(get(url)).andReturn().getResponse().getContentAsByteArray();
        return new ObjectMapper().readTree(bytes);
    }
}
