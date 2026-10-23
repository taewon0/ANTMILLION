package com.antmillion.kiwoom;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisChartRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisPeriodChartProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.service.KiwoomPeriodChartProvider;
import com.antmillion.kiwoom.service.KiwoomRestRateLimitTest;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.marketdata.dto.PeriodCandle;
import com.antmillion.marketdata.dto.PeriodChartRequest;
import com.antmillion.marketdata.provider.PeriodChartProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.TimeMeter;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 합성 자격증명·고정 시각·메모리 Redis/잠금·HTTP 대역만 사용하며 실제 설정/DB를 읽지 않습니다. */
public class KiwoomPeriodChartProviderTest {
    private static final String[] PERIODS = {"D", "W", "M", "Y"};
    private static final String[] API_IDS = {"ka10081", "ka10082", "ka10083", "ka10094"};
    private static final String[] FIELDS = {"stk_dt_pole_chart_qry", "stk_stk_pole_chart_qry",
            "stk_mth_pole_chart_qry", "stk_yr_pole_chart_qry"};
    private KiwoomTokenServiceTest.MutableClock clock;
    private KiwoomTokenServiceTest.MemoryRedis redis;
    private KiwoomTokenServiceTest.LockClient locks;
    private KiwoomAccessTokenRedisRepository repository;
    private KiwoomAccessTokenScope scope;
    private KiwoomRestRateLimiter limiter;
    private KiwoomTokenService tokens;
    private RestTemplate rest;
    private MockRestServiceServer server;
    private PeriodChartProvider provider;
    private final AtomicLong nanos = new AtomicLong();
    private boolean advanceTime = true;

    @Before
    public void setUp() {
        clock = new KiwoomTokenServiceTest.MutableClock();
        redis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        locks = new KiwoomTokenServiceTest.LockClient();
        repository = new KiwoomAccessTokenRedisRepository(redis, clock);
        scope = scope("virtual", "synthetic-key", "synthetic-secret");
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        limiter = KiwoomRestRateLimitTest.withClock(new TimeMeter() {
            @Override public long currentTimeNanos() {
                return advanceTime ? nanos.getAndAdd(1_000_000_000L) : nanos.get();
            }
            @Override public boolean isWallClockBased() { return false; }
        });
        tokens = tokens(scope);
        provider = new KiwoomPeriodChartProvider(tokens, rest, limiter);
        cacheToken(scope, "synthetic-token");
    }

    @Test
    public void allPeriodsUseTheirOwnApiAndResponseListWithKrxAdjustedBody() {
        for (int i = 0; i < PERIODS.length; i++) {
            expect(scope, API_IDS[i], null, "20261023", "synthetic-token")
                    .andRespond(withSuccess(body(FIELDS[i], row("20261023", "+071000")), MediaType.APPLICATION_JSON));
        }
        for (String period : PERIODS) {
            assertEquals(List.of(candle("20261023", "71000")), provider.getPeriodChart(request(period)));
        }
        assertEquals(1, redis.writes.get()); // 최초 합성 토큰 외에는 저장하지 않습니다.
        server.verify();
    }

    @Test
    public void continuationDoesNotCauseAnotherHttpOrTurnSuccessfulFirstResponseIntoLimitFailure() {
        advanceTime = false;
        expect(scope, "ka10081", null, "20261023", "synthetic-token").andRespond(withSuccess(
                body(FIELDS[0], row("20261023", "100")), MediaType.APPLICATION_JSON)
                .headers(continuation("unused-key")));
        assertEquals(List.of(candle("20261023", "100")), provider.getPeriodChart(request("D")));
        assertEquals(1, redis.reads.size());
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getPeriodChart(request("D")));
        assertEquals(1, redis.reads.size());
        server.verify();
    }

    @Test
    public void irrelevantContinuationHeadersDoNotInvalidateEmptyOrShortResults() {
        for (String key : Arrays.asList(null, "", "x".repeat(51))) {
            server.reset();
            expect(scope, "ka10081", null, "20261023", "synthetic-token").andRespond(withSuccess(
                    body(FIELDS[0]), MediaType.APPLICATION_JSON).headers(continuation(key)));
            assertTrue(provider.getPeriodChart(request("D")).isEmpty());
            server.verify();
        }
    }

    @Test
    public void allPeriodsReturnAtMostLatestHundredCandlesFromOneResponse() {
        LocalDate end = LocalDate.of(2026, 10, 23);
        for (int period = 0; period < PERIODS.length; period++) {
            for (int size : List.of(99, 100, 101)) {
                server.reset();
                String[] rows = new String[size];
                List<PeriodCandle> expected = new java.util.ArrayList<>();
                for (int i = 0; i < size; i++) {
                    String day = end.minusDays(i).format(DateTimeFormatter.BASIC_ISO_DATE);
                    rows[i] = row(day, Integer.toString(1000 - i));
                    if (i < 100) expected.add(candle(day, Integer.toString(1000 - i)));
                }
                expect(scope, API_IDS[period], null, "20261023", "synthetic-token").andRespond(withSuccess(
                        body(FIELDS[period], rows), MediaType.APPLICATION_JSON).headers(continuation("unused-key")));
                assertEquals(expected, provider.getPeriodChart(new PeriodChartRequest("005930", PERIODS[period],
                        end.minusYears(2), end)));
                server.verify();
            }
        }
    }

    @Test
    public void sameControllerRequestProducesSameCandlesAsActualKisHttpAdapterForAllPeriods() throws Exception {
        KisConfig kisConfig = new KisConfig() {
            @Override public String getBaseUrl() { return "https://openapi.koreainvestment.com:9443"; }
        };
        kisConfig.appKey = "synthetic-key";
        kisConfig.appSecret = "synthetic-secret";
        kisConfig.accountNo = "synthetic-account";
        kisConfig.acntPrdtCd = "01";
        RestTemplate kisRest = new RestTemplate();
        MockRestServiceServer kisServer = MockRestServiceServer.bindTo(kisRest).build();
        KisAccessTokenRedisRepository kisTokens =
                new KisAccessTokenRedisRepository(redis);
        kisTokens.save(new KisAccessTokenScope(kisConfig.getBaseUrl(),
                kisConfig.appKey, kisConfig.appSecret), "synthetic-kis-token", 86400);
        PeriodChartProvider kis = new KisPeriodChartProvider(
                new KisApiService(kisConfig, null, kisRest, kisTokens,
                        new KisChartRedisRepository(redis, new ObjectMapper()),
                        null, null, null, null, new RateLimitedKisApiClient(redis,
                        kisConfig, new KisRestRateLimitConfig(18, 1))));
        for (int i = 0; i < PERIODS.length; i++) {
            server.reset();
            kisServer.reset();
            PeriodChartRequest[] captured = new PeriodChartRequest[1];
            new KisController(null, null, value -> {
                captured[0] = value;
                return List.of();
            }, null, null, null).periodChart("005930", PERIODS[i]);
            PeriodChartRequest query = captured[0];
            LocalDate end = query.getEndDate();
            LocalDate expectedStart;
            LocalDate latest;
            LocalDate previous;
            switch (query.getPeriod()) {
                case "W":
                    expectedStart = end.minusWeeks(52);
                    latest = end.minusDays(end.getDayOfWeek().getValue() - 1);
                    previous = latest.minusWeeks(1);
                    break;
                case "M":
                    expectedStart = end.minusMonths(48);
                    latest = end.withDayOfMonth(1);
                    previous = latest.minusMonths(1);
                    break;
                case "Y":
                    expectedStart = end.minusYears(30);
                    latest = end.withDayOfYear(1);
                    previous = latest.minusYears(1);
                    break;
                default:
                    expectedStart = end.minusDays(100);
                    latest = end;
                    previous = latest.minusDays(1);
            }
            assertEquals(expectedStart, query.getStartDate());
            String first = latest.format(DateTimeFormatter.BASIC_ISO_DATE);
            String second = previous.format(DateTimeFormatter.BASIC_ISO_DATE);
            List<PeriodCandle> expected = Arrays.asList(candle(first, "71000"),
                    new PeriodCandle(second, "", null, "0", null), null);
            String kisJson = "{\"output2\":[{\"stck_bsop_date\":\"" + first + "\",\"stck_clpr\":\"71000\","
                    + "\"stck_oprc\":\"100\",\"stck_hgpr\":\"200\",\"stck_lwpr\":\"50\"},"
                    + "{\"stck_bsop_date\":\"" + second + "\",\"stck_clpr\":\"\",\"stck_hgpr\":\"0\"},null]}";
            String kisUrl = kisConfig.getBaseUrl() + "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"
                    + "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=005930&FID_INPUT_DATE_1="
                    + query.getStartDate().format(DateTimeFormatter.BASIC_ISO_DATE) + "&FID_INPUT_DATE_2="
                    + end.format(DateTimeFormatter.BASIC_ISO_DATE) + "&FID_PERIOD_DIV_CODE=" + query.getPeriod()
                    + "&FID_ORG_ADJ_PRC=0";
            kisServer.expect(requestTo(kisUrl)).andExpect(method(HttpMethod.GET))
                    .andExpect(header("tr_id", "FHKST03010100"))
                    .andRespond(withSuccess(kisJson, MediaType.APPLICATION_JSON));
            expect(scope, API_IDS[i], null, end.format(DateTimeFormatter.BASIC_ISO_DATE), "synthetic-token")
                    .andRespond(withSuccess(body(FIELDS[i], row(first, "-071000.00"),
                            "{\"dt\":\"" + second + "\",\"cur_prc\":\"\",\"high_pric\":\"0\"}", "null",
                            row(expectedStart.minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE), "1")),
                            MediaType.APPLICATION_JSON).headers(continuation("unused-key")));
            List<PeriodCandle> kisResult = kis.getPeriodChart(query);
            List<PeriodCandle> kiwoomResult = provider.getPeriodChart(query);
            assertEquals(expected, kisResult);
            assertEquals(kisResult, kiwoomResult);
            // 두 adapter의 결과를 기존 Controller에서 직렬화해 URL/필드/문자열/null 계약도 비교합니다.
            ObjectMapper mapper = new ObjectMapper();
            KisController kisController = new KisController(
                    null, null, value -> kisResult, null, null, null);
            KisController kiwoomController = new KisController(
                    null, null, value -> kiwoomResult, null, null, null);
            MockMvc kisMvc = MockMvcBuilders
                    .standaloneSetup(kisController).build();
            MockMvc kiwoomMvc = MockMvcBuilders
                    .standaloneSetup(kiwoomController).build();
            byte[] kisBody = kisMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/kis/periodChart/005930").param("period", query.getPeriod()))
                    .andReturn().getResponse().getContentAsByteArray();
            byte[] kiwoomBody = kiwoomMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/kis/periodChart/005930").param("period", query.getPeriod()))
                    .andReturn().getResponse().getContentAsByteArray();
            assertEquals(mapper.readTree(kisBody), mapper.readTree(kiwoomBody));
            assertEquals(5, mapper.readTree(kiwoomBody).get(0).size());
            kisServer.verify();
            server.verify();
        }
    }

    @Test
    public void inclusiveStartAndEndFilterSingleResponseWithoutChangingDuplicates() {
        expect(scope, "ka10081", null, "20261023", "synthetic-token").andRespond(withSuccess(
                body(FIELDS[0], row("20261023", "100"), row("20261023", "100"), row("20261001", "200"), row("20260930", "300")),
                MediaType.APPLICATION_JSON).headers(continuation(null)));
        assertEquals(List.of(candle("20261023", "100"), candle("20261023", "100"), candle("20261001", "200")),
                provider.getPeriodChart(request("D")));
        server.verify();
    }

    @Test
    public void singleDayUsesSameBaseAndInclusiveDate() {
        expect(scope, "ka10081", null, "20261001", "synthetic-token").andRespond(withSuccess(
                body(FIELDS[0], row("20261001", "100"), row("20260930", "200")), MediaType.APPLICATION_JSON));
        assertEquals(List.of(candle("20261001", "100")), provider.getPeriodChart(new PeriodChartRequest(
                "005930", "D", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1))));
        server.verify();
    }

    @Test
    public void pricesNormalizeSignsExactDecimalsAndLargeWonWithoutRounding() {
        expect(scope, "ka10081", null, "20261023", "synthetic-token").andRespond(withSuccess(
                body(FIELDS[0], "{\"dt\":\"20261023\",\"cur_prc\":\"-071000.00\","
                        + "\"open_pric\":\" +000.0 \",\"high_pric\":\"99999999999999999999\","
                        + "\"low_pric\":\"-1\"}"), MediaType.APPLICATION_JSON));
        assertEquals(List.of(new PeriodCandle("20261023", "71000", "0", "99999999999999999999", "1")),
                provider.getPeriodChart(request("D")));
        server.verify();
    }

    @Test
    public void nullRowsAndMissingOrBlankPricesArePreservedWithoutInventingZero() {
        expect(scope, "ka10081", null, "20261023", "synthetic-token").andRespond(withSuccess(
                body(FIELDS[0], "null", "{\"dt\":\"20261023\",\"cur_prc\":null,\"open_pric\":\"  \","
                        + "\"high_pric\":\"0\"}"), MediaType.APPLICATION_JSON));
        assertEquals(Arrays.asList(null, new PeriodCandle("20261023", null, "", "0", null)),
                provider.getPeriodChart(request("D")));
        server.verify();
    }

    @Test
    public void emptyListIsValidAndTokenExpiryIsNotExtendedOrChartCached() {
        Map<String, Long> expirations = new java.util.HashMap<>(redis.expirations);
        for (int i = 0; i < 2; i++) {
            expect(scope, "ka10081", null, "20261023", "synthetic-token")
                    .andRespond(withSuccess(body(FIELDS[0]), MediaType.APPLICATION_JSON));
        }
        assertTrue(provider.getPeriodChart(request("D")).isEmpty());
        clock.advanceSeconds(1);
        assertTrue(provider.getPeriodChart(request("D")).isEmpty());
        assertEquals(expirations, redis.expirations);
        assertEquals(1, redis.writes.get());
        assertEquals(1, redis.values.size());
        server.verify();
    }

    @Test
    public void invalidRequestsAndPeriodsStopBeforeTokenReadHttpOrBudgetUse() {
        advanceTime = false;
        for (PeriodChartRequest invalid : Arrays.asList(null,
                new PeriodChartRequest(null, "D", LocalDate.now(), LocalDate.now()),
                new PeriodChartRequest("005930_NX", "D", LocalDate.now(), LocalDate.now()),
                new PeriodChartRequest("bad", "D", LocalDate.now(), LocalDate.now()),
                new PeriodChartRequest("005930", null, LocalDate.now(), LocalDate.now()),
                new PeriodChartRequest("005930", "X", LocalDate.now(), LocalDate.now()),
                new PeriodChartRequest("005930", "D", null, LocalDate.now()),
                new PeriodChartRequest("005930", "D", LocalDate.now(), null),
                new PeriodChartRequest("005930", "D", LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 23)),
                new PeriodChartRequest("005930", "D", LocalDate.of(0, 1, 1), LocalDate.of(2026, 10, 23)),
                new PeriodChartRequest("005930", "D", LocalDate.of(2026, 10, 1), LocalDate.of(10000, 1, 1)))) {
            assertThrows(IllegalArgumentException.class, () -> provider.getPeriodChart(invalid));
        }
        assertTrue(redis.reads.isEmpty());
        expect(scope, "ka10081", null, "20261023", "synthetic-token")
                .andRespond(withSuccess(body(FIELDS[0]), MediaType.APPLICATION_JSON));
        assertTrue(provider.getPeriodChart(request("D")).isEmpty());
        server.verify();
    }

    @Test
    public void invalidDateFutureOrAscendingRowsFailWithoutReturningPartialData() {
        for (String rows : List.of("{}", "{\"dt\":null}", row("20260230", "100"),
                row("20261024", "100"), row("2026-10-23", "100"), row("00000101", "100"),
                row("20261022", "100") + "," + row("20261023", "200"))) {
            server.reset();
            expect(scope, "ka10081", null, "20261023", "synthetic-token")
                    .andRespond(withSuccess(body(FIELDS[0], rows), MediaType.APPLICATION_JSON));
            assertSanitizedFailure();
            server.verify();
        }
    }

    @Test
    public void everyOhlcPriceRejectsFractionsOrInvalidNumericForms() {
        for (String field : List.of("cur_prc", "open_pric", "high_pric", "low_pric")) {
            for (String value : List.of("1.01", "1e3", "1,000", "synthetic-sensitive")) {
                server.reset();
                expect(scope, "ka10081", null, "20261023", "synthetic-token").andRespond(withSuccess(
                        body(FIELDS[0], "{\"dt\":\"20261023\",\"" + field + "\":\"" + value + "\"}"),
                        MediaType.APPLICATION_JSON));
                assertSanitizedFailure();
                server.verify();
            }
        }
    }

    @Test
    public void wrongOrMissingListStockMismatchBusinessAndParsingErrorsFail() {
        for (String response : List.of("null", "{}", "{\"stk_dt_pole_chart_qry\":null}", body(FIELDS[1]),
                "{\"stk_cd\":\"000660\",\"stk_dt_pole_chart_qry\":[]}",
                "{\"return_code\":1,\"return_msg\":\"synthetic-sensitive\",\"stk_dt_pole_chart_qry\":[]}",
                "{\"return_code\":\"bad\",\"stk_dt_pole_chart_qry\":[]}", "not-json")) {
            server.reset();
            expect(scope, "ka10081", null, "20261023", "synthetic-token")
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            assertSanitizedFailure();
            server.verify();
        }
    }

    @Test
    public void httpAndTransportErrorsAreSanitizedWithoutRetry() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR)) {
            server.reset();
            expect(scope, "ka10081", null, "20261023", "synthetic-token")
                    .andRespond(withStatus(status).body("synthetic-sensitive"));
            assertSanitizedFailure();
            server.verify();
        }
        server.reset();
        expect(scope, "ka10081", null, "20261023", "synthetic-token").andRespond(request -> {
            throw new IOException("synthetic-sensitive");
        });
        assertSanitizedFailure();
        server.verify();
    }

    @Test
    public void tokenFailureConsumesBudgetAndStopsBeforeChartHttp() {
        advanceTime = false;
        redis.failReads = true;
        assertSanitizedFailure();
        redis.failReads = false;
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getPeriodChart(request("D")));
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void realPriceAndAllChartPeriodsShareBudgetAndRefill() {
        advanceTime = false;
        KiwoomAccessTokenScope real = scope("real", "synthetic-key", "synthetic-secret");
        cacheToken(real, "real-token");
        KiwoomTokenService realTokens = tokens(real);
        limiter.acquireCurrentPrice(realTokens);
        PeriodChartProvider realProvider = new KiwoomPeriodChartProvider(realTokens, rest, limiter);
        for (int i = 0; i < PERIODS.length; i++) {
            expect(real, API_IDS[i], null, "20261023", "real-token")
                    .andRespond(withSuccess(body(FIELDS[i]), MediaType.APPLICATION_JSON));
        }
        for (String period : PERIODS) assertTrue(realProvider.getPeriodChart(request(period)).isEmpty());
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireCurrentPrice(realTokens));
        int reads = redis.reads.size();
        assertThrows(KiwoomRateLimitExceededException.class, () -> realProvider.getPeriodChart(request("D")));
        assertEquals(reads, redis.reads.size());
        server.verify();
        server.reset();
        nanos.set(200_000_000L);
        expect(real, "ka10081", null, "20261023", "real-token")
                .andRespond(withSuccess(body(FIELDS[0]), MediaType.APPLICATION_JSON));
        assertTrue(realProvider.getPeriodChart(request("D")).isEmpty());
        server.verify();
    }

    @Test
    public void virtualBudgetsArePerTrAndIndependentOfCurrentPrice() {
        advanceTime = false;
        limiter.acquireCurrentPrice(tokens);
        for (int i = 0; i < PERIODS.length; i++) {
            expect(scope, API_IDS[i], null, "20261023", "synthetic-token")
                    .andRespond(withSuccess(body(FIELDS[i]), MediaType.APPLICATION_JSON));
        }
        for (String period : PERIODS) assertTrue(provider.getPeriodChart(request(period)).isEmpty());
        for (String period : PERIODS) {
            assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getPeriodChart(request(period)));
        }
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireCurrentPrice(tokens));
        server.verify();
    }

    @Test
    public void queryKeysPreserveExistingPriceKeysAndIsolateEnvironmentAndAppKey() {
        for (String mode : List.of("real", "virtual")) {
            KiwoomAccessTokenScope first = scope(mode, "key-a", "secret-a");
            assertEquals(first.getCurrentPriceBudgetKey(), first.getQueryBudgetKey("ka10001"));
            assertEquals(first.getQueryBudgetKey("ka10081"), scope(mode, "key-a", "secret-b").getQueryBudgetKey("ka10081"));
            assertNotEquals(first.getQueryBudgetKey("ka10081"), scope(mode, "key-b", "secret-a").getQueryBudgetKey("ka10081"));
        }
        assertNotEquals(scope("real", "key-a", "secret-a").getQueryBudgetKey("ka10081"),
                scope("virtual", "key-a", "secret-a").getQueryBudgetKey("ka10081"));
        assertThrows(IllegalArgumentException.class, () -> scope.getQueryBudgetKey("sensitive"));
        assertThrows(IllegalArgumentException.class, () -> scope.getQueryBudgetKey(null));
        assertThrows(IllegalArgumentException.class, () -> limiter.acquirePeriodChart(tokens, "ka10001"));
    }

    @Test
    public void secretAndTokenReplacementDoNotResetChartBudget() {
        advanceTime = false;
        limiter.acquirePeriodChart(tokens, "ka10081");
        KiwoomTokenService rotated = tokens(scope("virtual", "synthetic-key", "rotated-secret"));
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquirePeriodChart(rotated, "ka10081"));
        cacheToken(scope, "replacement-token");
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getPeriodChart(request("D")));
        assertTrue(redis.reads.isEmpty());
        server.verify();
    }

    @Test
    public void failedHttpConsumesBudgetAndDoesNotRetryOrReadTokenOnRejection() {
        advanceTime = false;
        expect(scope, "ka10081", null, "20261023", "synthetic-token").andRespond(withServerError());
        assertSanitizedFailure();
        int reads = redis.reads.size();
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getPeriodChart(request("D")));
        assertEquals(reads, redis.reads.size());
        server.verify();
    }

    @Test
    public void virtualTrBucketsActuallySeparateAppKeysAndEnvironments() {
        advanceTime = false;
        limiter.acquirePeriodChart(tokens, "ka10081");
        KiwoomTokenService otherApp = tokens(scope("virtual", "other-key", "synthetic-secret"));
        KiwoomTokenService real = tokens(scope("real", "synthetic-key", "synthetic-secret"));
        limiter.acquirePeriodChart(otherApp, "ka10081");
        limiter.acquirePeriodChart(real, "ka10081");
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquirePeriodChart(tokens, "ka10081"));
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquirePeriodChart(otherApp, "ka10081"));
        assertTrue(redis.reads.isEmpty());
        server.verify();
    }

    @Test
    public void firstMissIssuesTokenThenRequestsChartInSameEnvironment() {
        KiwoomAccessTokenScope real = scope("real", "synthetic-key", "synthetic-secret");
        server.expect(requestTo(real.getBaseUrl() + "/oauth2/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\":\"real-token\",\"token_type\":\"bearer\","
                        + "\"expires_dt\":\"20261024090000\",\"return_code\":0}", MediaType.APPLICATION_JSON));
        expect(real, "ka10081", null, "20261023", "real-token")
                .andRespond(withSuccess(body(FIELDS[0]), MediaType.APPLICATION_JSON));
        assertTrue(new KiwoomPeriodChartProvider(tokens(real), rest, limiter).getPeriodChart(request("D")).isEmpty());
        assertEquals(List.of(real.getLockKey()), locks.keys);
        assertEquals("synthetic-token", repository.findAccessToken(scope, 600).orElseThrow());
        server.verify();
    }

    @Test
    public void realAndVirtualRequestsUseOnlyTheirOwnTokenAndEndpoint() {
        KiwoomAccessTokenScope real = scope("real", "synthetic-key", "synthetic-secret");
        cacheToken(real, "real-token");
        for (boolean realFirst : List.of(true, false)) {
            server.reset();
            KiwoomAccessTokenScope first = realFirst ? real : scope;
            KiwoomAccessTokenScope second = realFirst ? scope : real;
            expect(first, "ka10081", null, "20261023", realFirst ? "real-token" : "synthetic-token")
                    .andRespond(withSuccess(body(FIELDS[0]), MediaType.APPLICATION_JSON));
            expect(second, "ka10081", null, "20261023", realFirst ? "synthetic-token" : "real-token")
                    .andRespond(withSuccess(body(FIELDS[0]), MediaType.APPLICATION_JSON));
            new KiwoomPeriodChartProvider(tokens(first), rest, limiter).getPeriodChart(request("D"));
            new KiwoomPeriodChartProvider(tokens(second), rest, limiter).getPeriodChart(request("D"));
            server.verify();
        }
    }

    @Test
    public void actualDebugLogsDoNotExposeBearerCredentialsOrRemoteMessage() {
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(RestTemplate.class);
        org.apache.log4j.Level previous = logger.getLevel();
        java.io.StringWriter output = new java.io.StringWriter();
        org.apache.log4j.WriterAppender appender = new org.apache.log4j.WriterAppender(
                new org.apache.log4j.PatternLayout("%m%n"), output);
        logger.addAppender(appender);
        logger.setLevel(org.apache.log4j.Level.DEBUG);
        try {
            expect(scope, "ka10081", null, "20261023", "synthetic-token").andRespond(withSuccess(
                    body(FIELDS[0], row("20261023", "100")).replace("\"return_code\":0",
                            "\"return_code\":0,\"return_msg\":\"synthetic-sensitive\""), MediaType.APPLICATION_JSON));
            assertEquals(1, provider.getPeriodChart(request("D")).size());
            assertTrue(output.toString().contains("Writing ["));
            for (String secret : List.of("synthetic-token", "synthetic-key", "synthetic-secret", "synthetic-sensitive")) {
                assertFalse(output.toString().contains(secret));
            }
            server.verify();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.close();
        }
    }

    private ResponseActions expect(KiwoomAccessTokenScope requestScope, String apiId, String key, String base,
                                   String token) {
        return server.expect(requestTo(requestScope.getBaseUrl() + "/api/dostk/chart"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("api-id", apiId))
                .andExpect(header("Authorization", "Bearer " + token))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(httpRequest -> {
                    assertEquals(key == null ? null : "Y", httpRequest.getHeaders().getFirst("cont-yn"));
                    assertEquals(key, httpRequest.getHeaders().getFirst("next-key"));
                    JsonNode json = new ObjectMapper().readTree(((MockClientHttpRequest) httpRequest).getBodyAsString());
                    assertEquals(3, json.size());
                    assertEquals("005930", json.get("stk_cd").asText());
                    assertEquals(base, json.get("base_dt").asText());
                    assertEquals("1", json.get("upd_stkpc_tp").asText());
                });
    }

    private void assertSanitizedFailure() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> provider.getPeriodChart(request("D")));
        assertNull(error.getCause());
        for (String secret : List.of("synthetic-sensitive", "synthetic-token", "synthetic-secret")) {
            assertFalse(error.getMessage().contains(secret));
        }
    }

    private static PeriodChartRequest request(String period) {
        return new PeriodChartRequest("005930", period, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 23));
    }

    private static String body(String field, String... rows) {
        return "{\"stk_cd\":\"005930\",\"return_code\":0,\"" + field + "\":[" + String.join(",", rows) + "]}";
    }

    private static HttpHeaders continuation(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("cont-yn", "Y");
        if (key != null) headers.set("next-key", key);
        return headers;
    }

    private static String row(String date, String price) {
        return "{\"dt\":\"" + date + "\",\"cur_prc\":\"" + price + "\",\"open_pric\":\"100\","
                + "\"high_pric\":\"200\",\"low_pric\":\"50\"}";
    }

    private static PeriodCandle candle(String date, String price) {
        return new PeriodCandle(date, price, "100", "200", "50");
    }

    private static KiwoomAccessTokenScope scope(String mode, String key, String secret) {
        return new KiwoomAccessTokenScope(mode, key, secret);
    }

    private KiwoomTokenService tokens(KiwoomAccessTokenScope tokenScope) {
        return new KiwoomTokenService(tokenScope, 600, rest, repository, locks.client(), clock);
    }

    private void cacheToken(KiwoomAccessTokenScope tokenScope, String token) {
        repository.save(tokenScope, token, clock.instant().plusSeconds(86400), 600);
    }
}
