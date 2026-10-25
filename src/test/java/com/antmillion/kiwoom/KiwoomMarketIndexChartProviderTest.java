package com.antmillion.kiwoom;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisMarketIndexChartRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisMarketIndexChartProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomMarketIndexChartRedisRepository;
import com.antmillion.kiwoom.service.KiwoomMarketIndexChartProvider;
import com.antmillion.kiwoom.service.KiwoomRestRateLimitTest;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.marketdata.dto.MarketIndexCandle;
import com.antmillion.marketdata.dto.MarketIndexChartRequest;
import com.antmillion.marketdata.provider.MarketIndexChartProvider;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 합성 인증·시각·메모리 Redis/잠금·HTTP 대역만 사용하며 실제 설정/DB를 읽지 않습니다. */
public class KiwoomMarketIndexChartProviderTest {
    private KiwoomTokenServiceTest.MutableClock clock;
    private KiwoomTokenServiceTest.MemoryRedis redis;
    private KiwoomTokenServiceTest.LockClient locks;
    private KiwoomAccessTokenRedisRepository repository;
    private KiwoomAccessTokenScope scope;
    private KiwoomRestRateLimiter limiter;
    private KiwoomTokenService tokens;
    private RestTemplate rest;
    private MockRestServiceServer server;
    private MarketIndexChartProvider provider;
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
        provider = provider(tokens);
        cacheToken(scope, "synthetic-token");
    }

    @Test
    public void bothIndicesMapCodeBaseDateAndOfficialHundredfoldValues() {
        for (String code : List.of("001", "101")) {
            expect(scope, code, "20261023", "synthetic-token").andRespond(withSuccess(body(code,
                    "{\"dt\":\"20261023\",\"cur_prc\":\"252127\",\"open_pric\":\"251064\","
                    + "\"high_pric\":\"252733\",\"low_pric\":\"249918\"}"), MediaType.APPLICATION_JSON));
        }
        for (String code : List.of("0001", "1001")) {
            assertEquals(List.of(new MarketIndexCandle("20261023", "2521.27", "2510.64", "2527.33", "2499.18")),
                    provider.getMarketIndexChart(request(code)));
        }
        server.verify();
    }

    @Test
    public void pointConversionIsExactForSignsZeroLeadingZerosAndLargeIntegers() {
        expect(scope, "001", "20261023", "synthetic-token").andRespond(withSuccess(body("001",
                "{\"dt\":\"20261023\",\"cur_prc\":\" -000250050 \",\"open_pric\":\"+1\","
                + "\"high_pric\":\"99999999999999999999\",\"low_pric\":\"-0\"}"), MediaType.APPLICATION_JSON));
        assertEquals(List.of(new MarketIndexCandle("20261023", "2500.50", "0.01", "999999999999999999.99", "0.00")),
                provider.getMarketIndexChart(request("0001")));
        server.verify();
    }

    @Test
    public void nullRowsBlankAndMissingValuesDuplicatesAndNewestOrderArePreserved() {
        expect(scope, "001", "20261023", "synthetic-token").andRespond(withSuccess(body("001", "null",
                row("20261023", "250050"), row("20261023", "250050"),
                "{\"dt\":\"20261022\",\"cur_prc\":\"  \",\"open_pric\":null,\"high_pric\":\"0\"}"),
                MediaType.APPLICATION_JSON));
        assertEquals(Arrays.asList(null, candle("20261023", "2500.50"), candle("20261023", "2500.50"),
                new MarketIndexCandle("20261022", "", null, "0.00", null)), provider.getMarketIndexChart(request("0001")));
        server.verify();
    }

    @Test
    public void bothIndicesReturnNewestAtMost100RowsWithoutPagination() {
        for (String code : List.of("0001", "1001")) {
            for (int count : List.of(99, 100, 101)) {
                setUp();
                List<String> rows = new ArrayList<>();
                List<MarketIndexCandle> expected = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    String day = request(code).getEndDate().minusDays(i).format(DateTimeFormatter.BASIC_ISO_DATE);
                    rows.add(row(day, "250050"));
                    if (i < 100) expected.add(candle(day, "2500.50"));
                }
                String remoteCode = code.equals("0001") ? "001" : "101";
                expect(scope, remoteCode, "20261023", "synthetic-token").andRespond(withSuccess(
                        body(remoteCode, rows.toArray(new String[0])), MediaType.APPLICATION_JSON).headers(continuation()));
                assertEquals(expected, provider.getMarketIndexChart(request(code)));
                assertEquals(1, redis.reads.size());
                server.verify();
            }
        }
    }

    @Test
    public void emptySingleResponseIsValidEvenWithContinuationHeaders() {
        expect(scope, "001", "20261023", "synthetic-token").andRespond(withSuccess(body("001"),
                MediaType.APPLICATION_JSON).headers(continuation()));
        assertTrue(provider.getMarketIndexChart(request("0001")).isEmpty());
        server.verify();
    }

    @Test
    public void repeatedQueriesUseHttpAndDoNotSaveChartsOrExtendTokenExpiry() {
        Map<String, Long> expirations = new java.util.HashMap<>(redis.expirations);
        for (int i = 0; i < 2; i++) expect(scope, "001", "20261023", "synthetic-token")
                .andRespond(withSuccess(body("001", row("20261023", Integer.toString(100 + i))), MediaType.APPLICATION_JSON));
        assertEquals("1.00", provider.getMarketIndexChart(request("0001")).get(0).getCloseValue());
        assertEquals("1.01", provider.getMarketIndexChart(request("0001")).get(0).getCloseValue());
        assertEquals(1, redis.writes.get());
        assertEquals(expirations, redis.expirations);
        server.verify();
    }

    @Test
    public void invalidRequestsFailBeforeBudgetTokenAndHttp() {
        advanceTime = false;
        List<MarketIndexChartRequest> invalid = Arrays.asList(null, new MarketIndexChartRequest("0001", null),
                new MarketIndexChartRequest("0001", LocalDate.of(0, 1, 1)),
                new MarketIndexChartRequest("0001", LocalDate.of(10000, 1, 1)));
        for (MarketIndexChartRequest query : invalid) assertThrows(IllegalArgumentException.class,
                () -> provider.getMarketIndexChart(query));
        for (String code : Arrays.asList(null, "", "001", "101", "2001", "0002", " 0001", "synthetic-sensitive")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> provider.getMarketIndexChart(request(code)));
            assertFalse(error.getMessage().contains("synthetic-sensitive"));
        }
        assertTrue(redis.reads.isEmpty());
        limiter.acquireMarketIndexChart(tokens); // 잘못된 요청이 예산도 소비하지 않았습니다.
        server.verify();
    }

    @Test
    public void firstAndLastSupportedYearSerializeAsEightDigits() {
        for (String date : List.of("00010101", "99991231")) {
            expect(scope, "001", date, "synthetic-token").andRespond(withSuccess(body("001", row(date, "1")),
                    MediaType.APPLICATION_JSON));
        }
        for (String date : List.of("00010101", "99991231")) {
            assertEquals(List.of(candle(date, "0.01")), provider.getMarketIndexChart(new MarketIndexChartRequest(
                    "0001", LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE))));
        }
        server.verify();
    }

    @Test
    public void invalidDatesFutureRowsAndAscendingRowsAreRejected() {
        for (String value : List.of("", "20260230", "00000101", "20261024", "2026-10-23", "synthetic-sensitive")) {
            server.reset();
            expect(scope, "001", "20261023", "synthetic-token").andRespond(withSuccess(body("001", row(value, "1")),
                    MediaType.APPLICATION_JSON));
            assertSanitizedFailure();
            server.verify();
        }
        server.reset();
        expect(scope, "001", "20261023", "synthetic-token").andRespond(withSuccess(body("001",
                row("20261022", "1"), "null", row("20261023", "1")), MediaType.APPLICATION_JSON));
        assertSanitizedFailure();
        server.verify();
    }

    @Test
    public void missingDateIsRejected() {
        expect(scope, "001", "20261023", "synthetic-token").andRespond(withSuccess(body("001", "{}"), MediaType.APPLICATION_JSON));
        assertSanitizedFailure();
        server.verify();
    }

    @Test
    public void invalidPointInAnyOhlcFieldIsRejectedWithoutRounding() {
        for (String field : List.of("cur_prc", "open_pric", "high_pric", "low_pric")) {
            for (String value : List.of("2500.50", "1e5", "NaN", "1,000", "+", "synthetic-sensitive")) {
                server.reset();
                expect(scope, "001", "20261023", "synthetic-token").andRespond(withSuccess(body("001",
                        "{\"dt\":\"20261023\",\"" + field + "\":\"" + value + "\"}"), MediaType.APPLICATION_JSON));
                assertSanitizedFailure();
                server.verify();
            }
        }
    }

    @Test
    public void missingBodyMissingListBusinessErrorsWrongIndexAndMalformedJsonAreRejected() {
        for (String value : List.of("", "null", "{}", "{\"inds_dt_pole_qry\":null}",
                "{\"return_code\":1,\"return_msg\":\"synthetic-sensitive\",\"inds_dt_pole_qry\":[]}",
                "{\"return_code\":\"synthetic-sensitive\"}", "{broken-synthetic-sensitive",
                body("101"), body("synthetic-sensitive"), "{\"inds_dt_pole_qry\":{}}")) {
            server.reset();
            expect(scope, "001", "20261023", "synthetic-token").andRespond(withSuccess(value, MediaType.APPLICATION_JSON));
            assertSanitizedFailure();
            server.verify();
        }
    }

    @Test
    public void optionalResponseCodeAndIndexMayBeAbsent() {
        expect(scope, "001", "20261023", "synthetic-token").andRespond(withSuccess(
                "{\"inds_dt_pole_qry\":[" + row("20261023", "1") + "]}", MediaType.APPLICATION_JSON));
        assertEquals(List.of(candle("20261023", "0.01")), provider.getMarketIndexChart(request("0001")));
        server.verify();
    }

    @Test
    public void unauthorizedRemoteLimitAndServerErrorsDoNotRetryOrExposeRemoteBody() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR)) {
            server.reset();
            expect(scope, "001", "20261023", "synthetic-token").andRespond(withStatus(status)
                    .body("synthetic-sensitive synthetic-token").contentType(MediaType.APPLICATION_JSON));
            assertSanitizedFailure();
            server.verify();
        }
    }

    @Test
    public void transportFailureIsSanitizedAndNotRetried() {
        expect(scope, "001", "20261023", "synthetic-token").andRespond(request -> {
            throw new java.io.IOException("synthetic-sensitive");
        });
        assertSanitizedFailure();
        server.verify();
    }

    @Test
    public void bothIndicesShareVirtualTrBudgetAndRejectionDoesNotReadToken() {
        advanceTime = false;
        expect(scope, "001", "20261023", "synthetic-token").andRespond(withSuccess(body("001"), MediaType.APPLICATION_JSON));
        provider.getMarketIndexChart(request("0001"));
        int reads = redis.reads.size();
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getMarketIndexChart(request("1001")));
        assertEquals(reads, redis.reads.size());
        server.verify();
    }

    @Test
    public void realIndexSharesBudgetWithCurrentPricePeriodAndMinuteChartsAndRefills() {
        advanceTime = false;
        KiwoomAccessTokenScope real = scope("real", "synthetic-key", "synthetic-secret");
        cacheToken(real, "real-token");
        KiwoomTokenService realTokens = tokens(real);
        limiter.acquireCurrentPrice(realTokens);
        limiter.acquirePeriodChart(realTokens, "ka10081");
        limiter.acquireMinuteChart(realTokens);
        limiter.acquireMarketIndexChart(realTokens);
        MarketIndexChartProvider realProvider = provider(realTokens);
        expect(real, "001", "20261023", "real-token").andRespond(withSuccess(body("001"), MediaType.APPLICATION_JSON));
        assertTrue(realProvider.getMarketIndexChart(request("0001")).isEmpty());
        int reads = redis.reads.size();
        assertThrows(KiwoomRateLimitExceededException.class, () -> realProvider.getMarketIndexChart(request("0001")));
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireCurrentPrice(realTokens));
        assertEquals(reads, redis.reads.size());
        server.verify();
        server.reset();
        nanos.set(200_000_000L);
        expect(real, "101", "20261023", "real-token").andRespond(withSuccess(body("101"), MediaType.APPLICATION_JSON));
        assertTrue(realProvider.getMarketIndexChart(request("1001")).isEmpty());
        server.verify();
    }

    @Test
    public void virtualIndexBudgetIsIndependentOfOtherQueryTrs() {
        advanceTime = false;
        limiter.acquireCurrentPrice(tokens);
        limiter.acquirePeriodChart(tokens, "ka10081");
        limiter.acquireMinuteChart(tokens);
        expect(scope, "001", "20261023", "synthetic-token").andRespond(withSuccess(body("001"), MediaType.APPLICATION_JSON));
        assertTrue(provider.getMarketIndexChart(request("0001")).isEmpty());
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireMarketIndexChart(tokens));
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireMinuteChart(tokens));
        server.verify();
    }

    @Test
    public void environmentAndAppKeysIsolateBudgetButSecretRotationDoesNotResetIt() {
        advanceTime = false;
        limiter.acquireMarketIndexChart(tokens);
        limiter.acquireMarketIndexChart(tokens(scope("virtual", "another-key", "synthetic-secret")));
        limiter.acquireMarketIndexChart(tokens(scope("real", "synthetic-key", "synthetic-secret")));
        assertThrows(KiwoomRateLimitExceededException.class,
                () -> limiter.acquireMarketIndexChart(tokens(scope("virtual", "synthetic-key", "rotated-secret"))));
        cacheToken(scope, "replacement-token");
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getMarketIndexChart(request("0001")));
        assertTrue(redis.reads.isEmpty());
        server.verify();
    }

    @Test
    public void failedHttpConsumesBudgetWithoutRetry() {
        advanceTime = false;
        expect(scope, "001", "20261023", "synthetic-token").andRespond(withServerError());
        assertSanitizedFailure();
        int reads = redis.reads.size();
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getMarketIndexChart(request("0001")));
        assertEquals(reads, redis.reads.size());
        server.verify();
    }

    @Test
    public void tokenFailureConsumesBudgetAndIsSanitizedBeforeHttp() {
        advanceTime = false;
        redis.failReads = true;
        assertSanitizedFailure();
        redis.failReads = false;
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getMarketIndexChart(request("0001")));
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void firstMissIssuesTokenThenQueriesSameEnvironment() {
        KiwoomAccessTokenScope real = scope("real", "synthetic-key", "synthetic-secret");
        server.expect(requestTo(real.getBaseUrl() + "/oauth2/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\":\"real-token\",\"token_type\":\"bearer\","
                        + "\"expires_dt\":\"20261024090000\",\"return_code\":0}", MediaType.APPLICATION_JSON));
        expect(real, "001", "20261023", "real-token").andRespond(withSuccess(body("001"), MediaType.APPLICATION_JSON));
        assertTrue(provider(tokens(real)).getMarketIndexChart(request("0001")).isEmpty());
        assertEquals(List.of(real.getLockKey()), locks.keys);
        assertEquals("synthetic-token", repository.findAccessToken(scope, 600).orElseThrow());
        server.verify();
    }

    @Test
    public void realAndVirtualUseOnlyTheirOwnTokenAndEndpointInBothOrders() {
        KiwoomAccessTokenScope real = scope("real", "synthetic-key", "synthetic-secret");
        cacheToken(real, "real-token");
        for (boolean realFirst : List.of(true, false)) {
            server.reset();
            KiwoomAccessTokenScope first = realFirst ? real : scope;
            KiwoomAccessTokenScope second = realFirst ? scope : real;
            expect(first, "001", "20261023", realFirst ? "real-token" : "synthetic-token")
                    .andRespond(withSuccess(body("001"), MediaType.APPLICATION_JSON));
            expect(second, "101", "20261023", realFirst ? "synthetic-token" : "real-token")
                    .andRespond(withSuccess(body("101"), MediaType.APPLICATION_JSON));
            provider(tokens(first)).getMarketIndexChart(request("0001"));
            provider(tokens(second)).getMarketIndexChart(request("1001"));
            server.verify();
        }
    }

    @Test
    public void bothActualHttpAdaptersProduceSameCommonDtoAndExistingMvcJson() throws Exception {
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
        kisTokens.save(new KisAccessTokenScope(config.getBaseUrl(), config.appKey, config.appSecret), "kis-token", 86400);
        MarketIndexChartProvider kis = new KisMarketIndexChartProvider(new KisApiService(config, null, kisRest,
                kisTokens, null, new KisMarketIndexChartRedisRepository(redis, new ObjectMapper()), null, null, null,
                new RateLimitedKisApiClient(redis, config, new KisRestRateLimitConfig(18, 1)), com.antmillion.marketdata.CircuitBreakerTestSupport.defaults()));
        for (String code : List.of("0001", "1001")) {
            server.reset();
            kisServer.reset();
            MarketIndexChartRequest[] captured = new MarketIndexChartRequest[1];
            new KisController(null, null, null, null, query -> { captured[0] = query; return List.of(); }, null)
                    .marketIndexChart(code);
            MarketIndexChartRequest query = captured[0];
            assertEquals(LocalDate.now(ZoneId.of("Asia/Seoul")), query.getEndDate());
            String date = query.getEndDate().format(DateTimeFormatter.BASIC_ISO_DATE);
            String previous = query.getEndDate().minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE);
            String kisJson = "{\"output2\":[{\"stck_bsop_date\":\"" + date + "\",\"bstp_nmix_prpr\":\"2500.50\","
                    + "\"bstp_nmix_oprc\":\"1.00\",\"bstp_nmix_hgpr\":\"2.00\",\"bstp_nmix_lwpr\":\"0.50\"},"
                    + "{\"stck_bsop_date\":\"" + previous + "\",\"bstp_nmix_prpr\":\"\",\"bstp_nmix_hgpr\":\"0.00\"},null]}";
            kisServer.expect(requestTo(config.getBaseUrl() + "/uapi/domestic-stock/v1/quotations/inquire-index-daily-price"
                    + "?FID_PERIOD_DIV_CODE=D&FID_COND_MRKT_DIV_CODE=U&FID_INPUT_ISCD=" + code + "&FID_INPUT_DATE_1=" + date))
                    .andExpect(method(HttpMethod.GET)).andExpect(header("tr_id", "FHPUP02120000"))
                    .andRespond(withSuccess(kisJson, MediaType.APPLICATION_JSON));
            String remoteCode = "0001".equals(code) ? "001" : "101";
            expect(scope, remoteCode, date, "synthetic-token").andRespond(withSuccess(body(remoteCode,
                    row(date, "-250050"), "{\"dt\":\"" + previous + "\",\"cur_prc\":\"\",\"high_pric\":\"0\"}", "null"),
                    MediaType.APPLICATION_JSON));
            List<MarketIndexCandle> kisResult = kis.getMarketIndexChart(query);
            List<MarketIndexCandle> result = provider.getMarketIndexChart(query);
            assertEquals(Arrays.asList(candle(date, "2500.50"), new MarketIndexCandle(previous, "", null, "0.00", null), null), result);
            assertEquals(kisResult, result);
            MockMvc kisMvc = MockMvcBuilders.standaloneSetup(new KisController(null, null, null, null, value -> kisResult, null)).build();
            MockMvc kiwoomMvc = MockMvcBuilders.standaloneSetup(new KisController(null, null, null, null, value -> result, null)).build();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode kisBody = mapper.readTree(kisMvc.perform(get("/api/kis/marketIndex/" + code)).andReturn().getResponse().getContentAsByteArray());
            JsonNode kiwoomBody = mapper.readTree(kiwoomMvc.perform(get("/api/kis/marketIndex/" + code)).andReturn().getResponse().getContentAsByteArray());
            assertEquals(kisBody, kiwoomBody);
            assertEquals(5, kiwoomBody.get(0).size());
            assertTrue(kiwoomBody.get(0).get("bstp_nmix_prpr").isTextual());
            kisServer.verify();
            server.verify();
        }
    }

    @Test
    public void debugLogsDoNotExposeCredentialsBearerOrRemoteMessage() {
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(RestTemplate.class);
        org.apache.log4j.Level previous = logger.getLevel();
        java.io.StringWriter output = new java.io.StringWriter();
        org.apache.log4j.WriterAppender appender = new org.apache.log4j.WriterAppender(new org.apache.log4j.PatternLayout("%m%n"), output);
        logger.addAppender(appender);
        logger.setLevel(org.apache.log4j.Level.DEBUG);
        try {
            expect(scope, "001", "20261023", "synthetic-token").andRespond(withSuccess(body("001", row("20261023", "1"))
                    .replace("\"return_code\":0", "\"return_code\":0,\"return_msg\":\"synthetic-sensitive\""), MediaType.APPLICATION_JSON));
            provider.getMarketIndexChart(request("0001"));
            assertTrue(output.toString().contains("Writing ["));
            for (String value : List.of("synthetic-token", "synthetic-key", "synthetic-secret", "synthetic-sensitive")) {
                assertFalse(output.toString().contains(value));
            }
            server.verify();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.close();
        }
    }

    private ResponseActions expect(KiwoomAccessTokenScope target, String code, String base, String token) {
        return server.expect(requestTo(target.getBaseUrl() + "/api/dostk/chart"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("api-id", "ka20006"))
                .andExpect(header("Authorization", "Bearer " + token))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(http -> {
                    assertNull(http.getHeaders().getFirst("cont-yn"));
                    assertNull(http.getHeaders().getFirst("next-key"));
                    JsonNode json = new ObjectMapper().readTree(((MockClientHttpRequest) http).getBodyAsString());
                    assertEquals(2, json.size());
                    assertEquals(code, json.get("inds_cd").asText());
                    assertEquals(base, json.get("base_dt").asText());
                });
    }

    private void assertSanitizedFailure() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> provider.getMarketIndexChart(request("0001")));
        assertNull(error.getCause());
        for (String value : List.of("synthetic-sensitive", "synthetic-token", "synthetic-key", "synthetic-secret")) {
            assertFalse(error.toString().contains(value));
        }
    }

    private MarketIndexChartProvider provider(KiwoomTokenService tokenService) {
        // 기존 계약 회귀는 저장 실패 대역에서 HTTP/제한/변환을 매번 검증합니다.
        KiwoomTokenServiceTest.MemoryRedis chartRedis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        chartRedis.failWrites = true;
        return new KiwoomMarketIndexChartProvider(tokenService, rest, limiter,
                new KiwoomMarketIndexChartRedisRepository(chartRedis, clock), com.antmillion.marketdata.CircuitBreakerTestSupport.defaults());
    }

    private static MarketIndexChartRequest request(String code) {
        return new MarketIndexChartRequest(code, LocalDate.of(2026, 10, 23));
    }

    private static String body(String code, String... rows) {
        return "{\"inds_cd\":\"" + code + "\",\"return_code\":0,\"inds_dt_pole_qry\":[" + String.join(",", rows) + "]}";
    }

    private static String row(String date, String value) {
        return "{\"dt\":\"" + date + "\",\"cur_prc\":\"" + value + "\",\"open_pric\":\"100\",\"high_pric\":\"200\",\"low_pric\":\"50\"}";
    }

    private static MarketIndexCandle candle(String date, String value) {
        return new MarketIndexCandle(date, value, "1.00", "2.00", "0.50");
    }

    private static HttpHeaders continuation() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("cont-yn", "Y");
        headers.set("next-key", "unused-key");
        return headers;
    }

    private static KiwoomAccessTokenScope scope(String mode, String key, String secret) {
        return new KiwoomAccessTokenScope(mode, key, secret);
    }

    private KiwoomTokenService tokens(KiwoomAccessTokenScope target) {
        return new KiwoomTokenService(target, 600, rest, repository, locks.client(), clock);
    }

    private void cacheToken(KiwoomAccessTokenScope target, String token) {
        repository.save(target, token, clock.instant().plusSeconds(86400), 600);
    }
}
