package com.antmillion.kiwoom;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisFrgnOrgnRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisInvestorFlowProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomInvestorFlowRedisRepository;
import com.antmillion.kiwoom.service.KiwoomInvestorFlowProvider;
import com.antmillion.kiwoom.service.KiwoomRestRateLimitTest;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.marketdata.dto.InvestorFlow;
import com.antmillion.marketdata.provider.InvestorFlowProvider;
import com.antmillion.marketdata.service.InvestorSignalService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 합성 인증·시각·메모리 Redis/잠금·HTTP·독립 MVC만 사용하며 실제 설정/외부 서비스를 로드하지 않습니다. */
public class KiwoomInvestorFlowProviderTest {
    private KiwoomTokenServiceTest.MutableClock clock;
    private KiwoomTokenServiceTest.MemoryRedis redis;
    private KiwoomTokenServiceTest.LockClient locks;
    private KiwoomAccessTokenRedisRepository repository;
    private KiwoomAccessTokenScope scope;
    private RestTemplate rest;
    private MockRestServiceServer server;
    private KiwoomRestRateLimiter limiter;
    private KiwoomTokenService tokens;
    private InvestorFlowProvider provider;
    private final AtomicLong nanos = new AtomicLong();
    private boolean advanceTime = true;

    @Before
    public void setUp() {
        clock = new KiwoomTokenServiceTest.MutableClock();
        redis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        locks = new KiwoomTokenServiceTest.LockClient();
        repository = new KiwoomAccessTokenRedisRepository(redis, clock);
        scope = scope("virtual", "synthetic-key");
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
    public void officialKrxQuantityNetPurchaseRequestUsesLatestTimeRegardlessOfOrder() {
        String early = row("090000", "999", "999");
        String latest = row("153000", "-12", "3");
        String middle = row("120000", "1", "1");
        for (String json : List.of(body(early, middle, latest), body(latest, middle, early), body(middle, latest, early))) {
            server.reset();
            expect().andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertEquals(new InvestorFlow("-12", "3", "-9"), query());
            server.verify();
        }
    }

    @Test
    public void signedQuantitiesAndCombinedSumNormalizeWithoutNarrowingLargeNumbers() {
        expect().andRespond(withSuccess(body(row("153000", " +00012 ", " -0003 ")), MediaType.APPLICATION_JSON));
        expect().andRespond(withSuccess(body(row("153000", "9223372036854775807", "1")), MediaType.APPLICATION_JSON));
        expect().andRespond(withSuccess(body(row("153000", "-9223372036854775808", "-1")), MediaType.APPLICATION_JSON));
        assertEquals(new InvestorFlow("12", "-3", "9"), query());
        assertEquals(new InvestorFlow("9223372036854775807", "1", "9223372036854775808"), query());
        assertEquals(new InvestorFlow("-9223372036854775808", "-1", "-9223372036854775809"), query());
        server.verify();
    }

    @Test
    public void missingAndBlankQuantitiesRemainMissingWithoutFallingBackToEarlierRow() {
        expect().andRespond(withSuccess(body(row("090000", "1", "2"), "{\"tm\":\"153000\",\"orgn\":\"-2\"}"), MediaType.APPLICATION_JSON));
        expect().andRespond(withSuccess(body(row("153000", "  ", "-2")), MediaType.APPLICATION_JSON));
        expect().andRespond(withSuccess(body("{\"tm\":\"153000\"}"), MediaType.APPLICATION_JSON));
        assertEquals(new InvestorFlow(null, "-2", null), query());
        assertEquals(new InvestorFlow("", "-2", null), query());
        assertEquals(new InvestorFlow(null, null, null), query());
        server.verify();
    }

    @Test
    public void signedZeroAndCancellationProduceExactZero() {
        expect().andRespond(withSuccess(body(row("153000", "-000", "+000")), MediaType.APPLICATION_JSON));
        expect().andRespond(withSuccess(body(row("153000", "-12", "+12")), MediaType.APPLICATION_JSON));
        assertEquals(new InvestorFlow("0", "0", "0"), query());
        assertEquals(new InvestorFlow("-12", "12", "0"), query());
        server.verify();
    }

    @Test
    public void emptyListReturnsMissingValuesAndRepeatedQueryStillUsesHttp() {
        Map<String, Long> expirations = new HashMap<>(redis.expirations);
        expect().andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        expect().andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        assertEquals(new InvestorFlow(null, null, null), query());
        assertEquals(new InvestorFlow(null, null, null), query());
        assertEquals(1, redis.writes.get());
        assertEquals(expirations, redis.expirations);
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void identicalLatestDuplicatesAreAcceptedButConflictingLatestDuplicatesFail() {
        expect().andRespond(withSuccess(body(row("153000", "01", "+02"), row("153000", "1", "2")), MediaType.APPLICATION_JSON));
        expect().andRespond(withSuccess(body(row("153000", "1", "2"), row("153000", "3", "2")), MediaType.APPLICATION_JSON));
        assertEquals(new InvestorFlow("1", "2", "3"), query());
        assertSanitizedFailure();
        server.verify();
    }

    @Test
    public void olderConflictingRowsDoNotMakeLatestSelectionDependOnResponseOrder() {
        String first = row("090000", "1", "2");
        String second = row("090000", "3", "4");
        String latest = row("153000", "5", "6");
        for (String json : List.of(body(first, second, latest), body(latest, first, second))) {
            server.reset();
            expect().andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertEquals(new InvestorFlow("5", "6", "11"), query());
            server.verify();
        }
    }

    @Test
    public void invalidStockCodesFailBeforeBudgetTokenOrHttp() {
        advanceTime = false;
        for (String code : new String[]{null, "", "12345", "005930_NX", "005930_AL", "00593a", " 005930"}) {
            assertThrows(IllegalArgumentException.class, () -> provider.getInvestorFlow(code));
        }
        assertTrue(redis.reads.isEmpty());
        limiter.acquireInvestorFlow(tokens);
        server.verify();
    }

    @Test
    public void missingNullListAndBodyFailWithoutInventingZeroQuantities() {
        for (String json : List.of("{}", "{\"opmr_invsr_trde_chart\":null}", "", "null")) {
            server.reset();
            expect().andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertSanitizedFailure();
            server.verify();
        }
    }

    @Test
    public void nullRowsMissingAndInvalidTimesRejectWholeResponse() {
        for (String entry : List.of("null", "{}", "{\"tm\":null}", "{\"tm\":\"90000\"}",
                row("240000", "1", "2"), row("126000", "1", "2"), row("125960", "1", "2"),
                row(" 090000", "1", "2"), row("synthetic-sensitive", "1", "2"))) {
            server.reset();
            expect().andRespond(withSuccess(body(row("153000", "1", "2"), entry), MediaType.APPLICATION_JSON));
            assertSanitizedFailure();
            server.verify();
        }
    }

    @Test
    public void malformedQuantitiesInAnyRowFailWithoutPartialReturn() {
        for (String value : List.of("1.5", "NaN", "1e3", "1,000", "--2", "+", "synthetic-sensitive")) {
            for (boolean foreign : List.of(false, true)) {
                server.reset();
                expect().andRespond(withSuccess(body(row("153000", "1", "2"),
                        row("090000", foreign ? value : "1", foreign ? "2" : value)), MediaType.APPLICATION_JSON));
                assertSanitizedFailure();
                server.verify();
            }
        }
    }

    @Test
    public void malformedJsonListAndFieldTypesAreSanitized() {
        for (String json : List.of("synthetic-sensitive", "{\"return_code\":\"synthetic-sensitive\"}",
                "{\"opmr_invsr_trde_chart\":{}}", body("{\"tm\":\"153000\",\"orgn\":{}}"))) {
            server.reset();
            expect().andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertSanitizedFailure();
            server.verify();
        }
    }

    @Test
    public void remoteReturnCodesRejectErrorsWithoutExposingMessages() {
        for (String key : List.of("return_code", "returnCode")) {
            server.reset();
            expect().andRespond(withSuccess("{\"" + key + "\":1,\"return_msg\":\"synthetic-sensitive\","
                    + "\"opmr_invsr_trde_chart\":[]}", MediaType.APPLICATION_JSON));
            assertSanitizedFailure();
            server.verify();
        }
    }

    @Test
    public void omittedReturnCodeAndCamelZeroAcceptValidList() {
        for (String json : List.of("{\"opmr_invsr_trde_chart\":[]}", "{\"returnCode\":0,\"opmr_invsr_trde_chart\":[]}")) {
            server.reset();
            expect().andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertEquals(new InvestorFlow(null, null, null), query());
            server.verify();
        }
    }

    @Test
    public void incompleteResponseFailsWithoutContinuationRequests() {
        for (String flag : List.of("Y", "unexpected")) {
            server.reset();
            HttpHeaders headers = new HttpHeaders();
            headers.set("cont-yn", flag);
            headers.set("next-key", "synthetic-sensitive");
            expect().andRespond(withSuccess(body(row("090000", "1", "2")), MediaType.APPLICATION_JSON).headers(headers));
            assertSanitizedFailure();
            server.verify();
        }
    }

    @Test
    public void noContinuationAndBlankFlagAcceptCompleteResponse() {
        for (String flag : List.of("N", "")) {
            server.reset();
            HttpHeaders headers = new HttpHeaders();
            headers.set("cont-yn", flag);
            expect().andRespond(withSuccess(body(), MediaType.APPLICATION_JSON).headers(headers));
            assertEquals(new InvestorFlow(null, null, null), query());
            server.verify();
        }
    }

    @Test
    public void http401429AndServerFailuresDoNotRetryOrExposeBodies() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR)) {
            server.reset();
            expect().andRespond(withStatus(status).body("synthetic-sensitive"));
            assertSanitizedFailure();
            server.verify();
        }
    }

    @Test
    public void connectionFailureIsSanitizedWithoutRetry() {
        expect().andRespond(withException(new java.io.IOException("synthetic-sensitive")));
        assertSanitizedFailure();
        server.verify();
    }

    @Test
    public void depletedBudgetBlocksTokenReadIssuanceAndHttp() {
        advanceTime = false;
        limiter.acquireInvestorFlow(tokens);
        redis.values.clear();
        KiwoomRateLimitExceededException error = assertThrows(KiwoomRateLimitExceededException.class, this::query);
        assertEquals(1, error.getRetryAfterSeconds());
        assertTrue(redis.reads.isEmpty());
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void remoteFailureConsumesBudgetAndRefillAllowsNextQuery() {
        advanceTime = false;
        expect().andRespond(withServerError());
        expect().andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        assertSanitizedFailure();
        assertThrows(KiwoomRateLimitExceededException.class, this::query);
        nanos.addAndGet(1_000_000_000L);
        assertEquals(new InvestorFlow(null, null, null), query());
        server.verify();
    }

    @Test
    public void conversionAndIncompleteResponseFailuresDoNotRefundBudget() {
        advanceTime = false;
        expect().andRespond(withSuccess(body(row("153000", "bad", "2")), MediaType.APPLICATION_JSON));
        assertSanitizedFailure();
        assertThrows(KiwoomRateLimitExceededException.class, this::query);
        nanos.addAndGet(1_000_000_000L);
        HttpHeaders headers = new HttpHeaders();
        headers.set("cont-yn", "Y");
        server.verify();
        server.reset();
        expect().andRespond(withSuccess(body(), MediaType.APPLICATION_JSON).headers(headers));
        assertSanitizedFailure();
        assertThrows(KiwoomRateLimitExceededException.class, this::query);
        server.verify();
    }

    @Test
    public void tokenReadAndIssuanceFailuresAreSanitizedAndConsumeBudget() {
        advanceTime = false;
        redis.failReads = true;
        assertSanitizedFailure();
        assertThrows(KiwoomRateLimitExceededException.class, this::query);
        redis.failReads = false;
        redis.values.clear();
        nanos.addAndGet(1_000_000_000L);
        server.expect(requestTo(scope.getBaseUrl() + "/oauth2/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("synthetic-sensitive"));
        assertSanitizedFailure();
        assertThrows(KiwoomRateLimitExceededException.class, this::query);
        assertEquals(1, locks.unlocks.get());
        server.verify();
    }

    @Test
    public void realBudgetIsSharedWithEveryOtherQueryInBothDirections() {
        advanceTime = false;
        KiwoomTokenService real = tokens(scope("real", "synthetic-key"));
        for (boolean flowFirst : List.of(false, true)) {
            nanos.addAndGet(1_000_000_000L);
            for (int i = 0; i < 5; i++) {
                if (flowFirst) limiter.acquireInvestorFlow(real);
                else limiter.acquireCurrentPrice(real);
            }
            assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireInvestorFlow(real));
            assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireCurrentPrice(real));
            assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquirePeriodChart(real, "ka10081"));
            assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireMinuteChart(real));
            assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireMarketIndexChart(real));
            assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireStockRanking(real));
        }
    }

    @Test
    public void virtualBudgetIsSharedAcrossAdapterInstancesButSeparateFromOtherTrsKeysAndReal() {
        advanceTime = false;
        expect().andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        query();
        int reads = redis.reads.size();
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider(tokens(scope)).getInvestorFlow("0000A1"));
        assertEquals(reads, redis.reads.size());
        limiter.acquireCurrentPrice(tokens);
        limiter.acquirePeriodChart(tokens, "ka10081");
        limiter.acquireMinuteChart(tokens);
        limiter.acquireMarketIndexChart(tokens);
        limiter.acquireStockRanking(tokens);
        limiter.acquireInvestorFlow(tokens(scope("virtual", "other-key")));
        limiter.acquireInvestorFlow(tokens(scope("real", "synthetic-key")));
        server.verify();
    }

    @Test
    public void realAndVirtualUseOwnEndpointsAndTokensInBothOrders() {
        KiwoomAccessTokenScope real = scope("real", "synthetic-key");
        cacheToken(real, "real-token");
        for (boolean realFirst : List.of(false, true)) {
            server.reset();
            KiwoomAccessTokenScope first = realFirst ? real : scope;
            KiwoomAccessTokenScope second = realFirst ? scope : real;
            expect(first, realFirst ? "real-token" : "synthetic-token").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
            expect(second, realFirst ? "synthetic-token" : "real-token").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
            provider(tokens(first)).getInvestorFlow("0000A1");
            provider(tokens(second)).getInvestorFlow("0000A1");
            server.verify();
        }
    }

    @Test
    public void missingTokenIssuesWithinItsScopeBeforeQuery() {
        redis.values.clear();
        server.expect(requestTo(scope.getBaseUrl() + "/oauth2/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\":\"issued-token\",\"token_type\":\"bearer\",\"expires_dt\":\"20261024090000\",\"return_code\":0}", MediaType.APPLICATION_JSON));
        expect(scope, "issued-token").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        assertEquals(new InvestorFlow(null, null, null), query());
        assertEquals(List.of(scope.getLockKey()), locks.keys);
        server.verify();
    }

    @Test
    public void actualKisAndKiwoomAdaptersKeepCommonDtoAndSignalMvcJson() throws Exception {
        for (String[] values : new String[][]{{"12", "3", "15", "GREEN"}, {"-12", "-3", "-15", "RED"},
                {"0", "3", "3", "YELLOW"}, {"12", "-3", "9", "YELLOW"}, {null, "3", null, null}}) {
            server.reset();
            KisConfig config = new KisConfig() {
                @Override public String getBaseUrl() { return "https://kis.test"; }
            };
            config.appKey = "synthetic-key";
            config.appSecret = "synthetic-secret";
            config.accountNo = "synthetic-account";
            config.acntPrdtCd = "01";
            RestTemplate kisRest = new RestTemplate();
            MockRestServiceServer kisServer = MockRestServiceServer.bindTo(kisRest).build();
            KiwoomTokenServiceTest.MemoryRedis kisRedis = new KiwoomTokenServiceTest.MemoryRedis(clock);
            KisAccessTokenRedisRepository kisTokens = new KisAccessTokenRedisRepository(kisRedis);
            kisTokens.save(new KisAccessTokenScope(config.getBaseUrl(), config.appKey, config.appSecret), "kis-token", 86400);
            InvestorFlowProvider kis = new KisInvestorFlowProvider(new KisApiService(config, null, kisRest, kisTokens, null, null,
                    null, new KisFrgnOrgnRedisRepository(kisRedis, new ObjectMapper()), null,
                    new RateLimitedKisApiClient(kisRedis, config, new KisRestRateLimitConfig(18, 1))));
            kisServer.expect(request -> {
                assertEquals(HttpMethod.GET, request.getMethod());
                assertEquals("HHPTJ04160200", request.getHeaders().getFirst("tr_id"));
                assertTrue(request.getURI().toString().contains("MKSC_SHRN_ISCD=0000A1"));
            }).andRespond(withSuccess("{\"output2\":[{\"frgn_fake_ntby_qty\":" + json(values[0])
                    + ",\"orgn_fake_ntby_qty\":" + json(values[1]) + ",\"sum_fake_ntby_qty\":" + json(values[2]) + "}]}", MediaType.APPLICATION_JSON));
            String response = body(row("153000", values[0], values[1]));
            expect().andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            expect().andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            assertEquals(kis.getInvestorFlow("0000A1"), query());
            JsonNode expected = mvcJson(kis);
            JsonNode actual = mvcJson(provider);
            assertEquals(expected, actual);
            assertEquals(1, actual.size());
            assertTrue(actual.has("signalColor"));
            if (values[3] == null) assertTrue(actual.get("signalColor").isNull());
            else assertEquals(values[3], actual.get("signalColor").asText());
            kisServer.verify();
            server.verify();
        }
    }

    @Test
    public void commonSignalRetainsBlankAndIndividualOverflowFailures() {
        for (String value : List.of("", "9223372036854775808")) {
            server.reset();
            expect().andRespond(withSuccess(body(row("153000", value, "1")), MediaType.APPLICATION_JSON));
            assertThrows(NumberFormatException.class, () -> new InvestorSignalService(provider).getSignalColor("0000A1"));
            server.verify();
        }
    }

    @Test
    public void debugLogsDoNotExposeCredentialsTokenOrRemoteMessage() {
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(RestTemplate.class);
        org.apache.log4j.Level previous = logger.getLevel();
        java.io.StringWriter output = new java.io.StringWriter();
        org.apache.log4j.WriterAppender appender = new org.apache.log4j.WriterAppender(new org.apache.log4j.PatternLayout("%m%n"), output);
        logger.addAppender(appender);
        logger.setLevel(org.apache.log4j.Level.DEBUG);
        try {
            expect().andRespond(withSuccess(body(row("153000", "1", "2")).replace("\"return_code\":0",
                    "\"return_code\":0,\"return_msg\":\"synthetic-sensitive\""), MediaType.APPLICATION_JSON));
            query();
            assertTrue(output.toString().contains("Writing ["));
            for (String value : List.of("synthetic-token", "synthetic-key", "synthetic-secret", "synthetic-sensitive")) assertFalse(output.toString().contains(value));
            server.verify();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.close();
        }
    }

    private JsonNode mvcJson(InvestorFlowProvider target) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new KisController(new InvestorSignalService(target), null, null, null, null, null)).build();
        org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(get("/api/kis/foreigner-organization/0000A1"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        return new ObjectMapper().readTree(response.getContentAsByteArray());
    }

    private InvestorFlow query() { return provider.getInvestorFlow("0000A1"); }

    private ResponseActions expect() { return expect(scope, "synthetic-token"); }

    private ResponseActions expect(KiwoomAccessTokenScope target, String token) {
        return server.expect(requestTo(target.getBaseUrl() + "/api/dostk/chart"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("api-id", "ka10064"))
                .andExpect(header("Authorization", "Bearer " + token))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(http -> {
                    assertNull(http.getHeaders().getFirst("cont-yn"));
                    assertNull(http.getHeaders().getFirst("next-key"));
                    JsonNode json = new ObjectMapper().readTree(((MockClientHttpRequest) http).getBodyAsString());
                    assertEquals(4, json.size());
                    assertEquals("000", json.get("mrkt_tp").asText());
                    assertEquals("2", json.get("amt_qty_tp").asText());
                    assertEquals("0", json.get("trde_tp").asText());
                    assertEquals("0000A1", json.get("stk_cd").asText());
                });
    }

    private void assertSanitizedFailure() {
        IllegalStateException error = assertThrows(IllegalStateException.class, this::query);
        assertNull(error.getCause());
        for (String value : List.of("synthetic-sensitive", "synthetic-token", "synthetic-key", "synthetic-secret")) assertFalse(error.toString().contains(value));
    }

    private InvestorFlowProvider provider(KiwoomTokenService target) {
        // 원격 계약 회귀는 저장 장애 대역으로 매번 HTTP를 확인합니다. 캐시 통합은 전용 테스트에서 검증합니다.
        KiwoomTokenServiceTest.MemoryRedis cacheRedis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        cacheRedis.failWrites = true;
        return new KiwoomInvestorFlowProvider(target, rest, limiter,
                new KiwoomInvestorFlowRedisRepository(cacheRedis, clock));
    }

    private KiwoomTokenService tokens(KiwoomAccessTokenScope target) {
        return new KiwoomTokenService(target, 600, rest, repository, locks.client(), clock);
    }

    private void cacheToken(KiwoomAccessTokenScope target, String token) {
        repository.save(target, token, clock.instant().plusSeconds(86400), 600);
    }

    private static KiwoomAccessTokenScope scope(String mode, String key) {
        return new KiwoomAccessTokenScope(mode, key, "synthetic-secret");
    }

    private static String body(String... rows) {
        return "{\"return_code\":0,\"opmr_invsr_trde_chart\":[" + String.join(",", rows) + "]}";
    }

    private static String row(String time, String foreign, String organization) {
        return "{\"tm\":" + json(time) + ",\"frgnr_invsr\":" + json(foreign) + ",\"orgn\":" + json(organization) + "}";
    }

    private static String json(String value) { return value == null ? "null" : "\"" + value + "\""; }
}
