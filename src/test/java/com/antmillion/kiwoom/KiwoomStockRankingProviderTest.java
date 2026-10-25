package com.antmillion.kiwoom;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisStockVolumeRankRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisStockRankingProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomStockRankingRedisRepository;
import com.antmillion.kiwoom.service.KiwoomRestRateLimitTest;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomStockRankingProvider;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.marketdata.dto.RankedStock;
import com.antmillion.marketdata.provider.StockRankingProvider;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 합성 인증·시각·메모리 Redis/잠금·HTTP만 사용하며 DB/개발 properties를 로드하지 않습니다. */
public class KiwoomStockRankingProviderTest {
    private KiwoomTokenServiceTest.MutableClock clock;
    private KiwoomTokenServiceTest.MemoryRedis redis;
    private KiwoomTokenServiceTest.LockClient locks;
    private KiwoomAccessTokenRedisRepository repository;
    private KiwoomAccessTokenScope scope;
    private RestTemplate rest;
    private MockRestServiceServer server;
    private KiwoomRestRateLimiter limiter;
    private KiwoomTokenService tokens;
    private StockRankingProvider provider;
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
    public void tradingValueAndDailyVolumeUseOfficialKrxSortAndExclusionsInBothOrders() {
        for (boolean volumeFirst : List.of(false, true)) {
            server.reset();
            expect(scope, volumeFirst, "synthetic-token").andRespond(withSuccess(body(row("005930", "-71000")), MediaType.APPLICATION_JSON));
            expect(scope, !volumeFirst, "synthetic-token").andRespond(withSuccess(body(row("005930", "+72000")), MediaType.APPLICATION_JSON));
            assertEquals("71000", query(volumeFirst).get(0).getCurrentPrice());
            assertEquals("72000", query(!volumeFirst).get(0).getCurrentPrice());
            server.verify();
        }
    }

    @Test
    public void unitsSignsLeadingZerosAndLargeIntegersAreNormalizedWithoutOverflow() {
        expect(false).andRespond(withSuccess(body("{\"stk_cd\":\"0000A1\",\"stk_nm\":\"합성종목\","
                + "\"cur_prc\":\" -00071000 \",\"trde_qty\":\"+99999999999999999999999\",\"flu_rt\":\" -1.20 \"}"), MediaType.APPLICATION_JSON));
        assertEquals(List.of(new RankedStock("합성종목", "0000A1", "1", "71000", "99999999999999999999999", "-1.20", false)), query(false));
        server.verify();
    }

    @Test
    public void nullRowsMissingBlankValuesAndDuplicatesKeepOriginalPositions() {
        expect(false).andRespond(withSuccess(body("null", "{\"stk_cd\":\"\",\"cur_prc\":\"  \",\"trde_qty\":null}",
                row("005930", "1"), row("005930", "1"), "{}"), MediaType.APPLICATION_JSON));
        assertEquals(Arrays.asList(null, new RankedStock(null, "", "2", "", null, null, false),
                stock("005930", "3", "1"), stock("005930", "4", "1"), new RankedStock(null, null, "5", null, null, null, false)), query(false));
        server.verify();
    }

    @Test
    public void rankUsesResponsePositionRatherThanRemoteUnknownFieldOrLocalSorting() {
        expect(true).andRespond(withSuccess(body(row("000002", "1").replace("}", ",\"rank\":\"99\",\"isFavorite\":true}"),
                row("000001", "2")), MediaType.APPLICATION_JSON));
        List<RankedStock> result = query(true);
        assertEquals(List.of(stock("000002", "1", "1"), stock("000001", "2", "2")), result);
        assertFalse(result.get(0).getFavorite());
        server.verify();
    }

    @Test
    public void singleResponseReturnsAtMostThirtyWithoutContinuationCalls() {
        for (int size : List.of(29, 30, 31)) {
            server.reset();
            List<String> rows = new ArrayList<>();
            for (int i = 0; i < size; i++) rows.add(row("005930", "1"));
            HttpHeaders headers = new HttpHeaders();
            headers.set("cont-yn", "Y");
            headers.set("next-key", "unused-key");
            expect(false).andRespond(withSuccess(body(rows.toArray(new String[0])), MediaType.APPLICATION_JSON).headers(headers));
            List<RankedStock> result = query(false);
            assertEquals(Math.min(30, size), result.size());
            assertEquals(Integer.toString(result.size()), result.get(result.size() - 1).getRank());
            server.verify();
        }
    }

    @Test
    public void emptyListIsValidForBothQueries() {
        expect(false).andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        expect(true).andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        assertTrue(query(false).isEmpty());
        assertTrue(query(true).isEmpty());
        server.verify();
    }

    @Test
    public void cacheWriteFailurePreservesRepeatedHttpAndTokenExpiry() {
        Map<String, Long> expirations = new java.util.HashMap<>(redis.expirations);
        for (int i = 0; i < 2; i++) expect(false).andRespond(withSuccess(body(row("005930", Integer.toString(i))), MediaType.APPLICATION_JSON));
        assertEquals("0", query(false).get(0).getCurrentPrice());
        assertEquals("1", query(false).get(0).getCurrentPrice());
        assertEquals(1, redis.writes.get());
        assertEquals(expirations, redis.expirations);
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void zeroAndPositiveRateSignsArePreserved() {
        expect(false).andRespond(withSuccess(body("{\"cur_prc\":\"-0\",\"trde_qty\":\"000\",\"flu_rt\":\"+0.00\"}",
                "{\"cur_prc\":\"99999999999999999999\",\"flu_rt\":\"+1.23\"}"), MediaType.APPLICATION_JSON));
        List<RankedStock> result = query(false);
        assertEquals(new RankedStock(null, null, "1", "0", "0", "+0.00", false), result.get(0));
        assertEquals("99999999999999999999", result.get(1).getCurrentPrice());
        assertEquals("+1.23", result.get(1).getChangeRate());
        server.verify();
    }

    @Test
    public void missingNullListAndEmptyBodyAreFailures() {
        for (String json : List.of("{}", "{\"tdy_trde_qty_upper\":null}", "", "null")) {
            server.reset();
            expect(false).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertSanitizedFailure(false);
            server.verify();
        }
    }

    @Test
    public void snakeAndOfficialExampleCamelReturnCodesRejectRemoteErrors() {
        for (String key : List.of("return_code", "returnCode")) {
            server.reset();
            expect(false).andRespond(withSuccess("{\"" + key + "\":1,\"return_msg\":\"synthetic-sensitive\","
                    + "\"tdy_trde_qty_upper\":[]}", MediaType.APPLICATION_JSON));
            assertSanitizedFailure(false);
            server.verify();
        }
    }

    @Test
    public void omittedReturnCodeAndCamelZeroStillAcceptValidList() {
        for (String json : List.of("{\"tdy_trde_qty_upper\":[]}", "{\"returnCode\":0,\"tdy_trde_qty_upper\":[]}")) {
            server.reset();
            expect(false).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertTrue(query(false).isEmpty());
            server.verify();
        }
    }

    @Test
    public void malformedNumericFieldsRejectWholeResultWithoutPartialReturn() {
        for (String field : List.of("cur_prc", "trde_qty", "flu_rt")) {
            for (String value : List.of("NaN", "1e3", "1,000", "synthetic-sensitive")) {
                server.reset();
                expect(false).andRespond(withSuccess(body(row("005930", "1"), "{\"" + field + "\":\"" + value + "\"}"), MediaType.APPLICATION_JSON));
                assertSanitizedFailure(false);
                server.verify();
            }
        }
    }

    @Test
    public void fractionalPriceFractionalOrNegativeVolumeAndNonKrxCodeAreRejected() {
        for (String row : List.of("{\"cur_prc\":\"1.5\"}", "{\"trde_qty\":\"1.5\"}",
                "{\"trde_qty\":\"-1\"}", "{\"stk_cd\":\"005930_NX\"}", "{\"stk_cd\":\"bad\"}")) {
            server.reset();
            expect(true).andRespond(withSuccess(body(row), MediaType.APPLICATION_JSON));
            assertSanitizedFailure(true);
            server.verify();
        }
    }

    @Test
    public void malformedJsonAndFieldTypesAreSanitized() {
        for (String json : List.of("synthetic-sensitive", "{\"return_code\":\"synthetic-sensitive\"}",
                "{\"tdy_trde_qty_upper\":{}}", "{\"tdy_trde_qty_upper\":[{\"cur_prc\":{}}]}")) {
            server.reset();
            expect(false).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertSanitizedFailure(false);
            server.verify();
        }
    }

    @Test
    public void http401429AndServerFailuresAreSanitizedWithoutRetry() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR)) {
            server.reset();
            expect(false).andRespond(withStatus(status).body("synthetic-sensitive"));
            assertSanitizedFailure(false);
            server.verify();
        }
    }

    @Test
    public void connectionFailureIsSanitizedWithoutRetry() {
        expect(false).andRespond(withException(new java.io.IOException("synthetic-sensitive")));
        assertSanitizedFailure(false);
        server.verify();
    }

    @Test
    public void bothSortsShareVirtualBudgetBeforeAnyTokenReadOrHttp() {
        advanceTime = false;
        expect(false).andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        expect(true).andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        query(false);
        int reads = redis.reads.size();
        KiwoomRateLimitExceededException error = assertThrows(KiwoomRateLimitExceededException.class, () -> query(true));
        assertEquals(1, error.getRetryAfterSeconds());
        assertEquals(reads, redis.reads.size());
        nanos.addAndGet(1_000_000_000L);
        query(true);
        reads = redis.reads.size();
        assertThrows(KiwoomRateLimitExceededException.class, () -> query(false));
        assertEquals(reads, redis.reads.size());
        server.verify();
    }

    @Test
    public void depletedBudgetDoesNotAttemptTokenIssuance() {
        advanceTime = false;
        limiter.acquireStockRanking(tokens);
        redis.values.clear();
        assertThrows(KiwoomRateLimitExceededException.class, () -> query(false));
        assertTrue(redis.reads.isEmpty());
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void remoteFailureStillConsumesBudgetAndRefillAllowsNextQuery() {
        advanceTime = false;
        expect(false).andRespond(withServerError());
        expect(true).andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        assertSanitizedFailure(false);
        assertThrows(KiwoomRateLimitExceededException.class, () -> query(true));
        nanos.addAndGet(1_000_000_000L);
        assertTrue(query(true).isEmpty());
        server.verify();
    }

    @Test
    public void tokenFailureIsSanitizedAndConsumesBudget() {
        advanceTime = false;
        redis.failReads = true;
        assertSanitizedFailure(false);
        assertThrows(KiwoomRateLimitExceededException.class, () -> query(false));
        server.verify();
    }

    @Test
    public void issuanceFailureDoesNotQueryRankingRetryOrRefundBudget() {
        advanceTime = false;
        redis.values.clear();
        server.expect(requestTo(scope.getBaseUrl() + "/oauth2/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("synthetic-sensitive"));
        assertSanitizedFailure(false);
        assertThrows(KiwoomRateLimitExceededException.class, () -> query(true));
        assertEquals(0, redis.values.size());
        assertEquals(1, locks.unlocks.get());
        server.verify();
    }

    @Test
    public void conversionFailureDoesNotReturnPartialRowsOrRefundBudget() {
        advanceTime = false;
        expect(false).andRespond(withSuccess(body(row("005930", "1"), row("005930", "bad")), MediaType.APPLICATION_JSON));
        assertSanitizedFailure(false);
        assertThrows(KiwoomRateLimitExceededException.class, () -> query(true));
        server.verify();
    }

    @Test
    public void realBudgetIsSharedWithCurrentPricePeriodMinuteAndIndexQueriesInBothDirections() {
        advanceTime = false;
        KiwoomTokenService real = tokens(scope("real", "synthetic-key"));
        for (boolean rankingFirst : List.of(false, true)) {
            nanos.addAndGet(1_000_000_000L);
            for (int i = 0; i < 5; i++) {
                if (rankingFirst) limiter.acquireStockRanking(real);
                else limiter.acquireCurrentPrice(real);
            }
            assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireStockRanking(real));
            assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireCurrentPrice(real));
            assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquirePeriodChart(real, "ka10081"));
            assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireMinuteChart(real));
            assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireMarketIndexChart(real));
        }
    }

    @Test
    public void virtualRankingBudgetIsIsolatedFromOtherTrsAndAppKeys() {
        advanceTime = false;
        limiter.acquireStockRanking(tokens);
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireStockRanking(tokens));
        limiter.acquireCurrentPrice(tokens);
        limiter.acquirePeriodChart(tokens, "ka10081");
        limiter.acquireMinuteChart(tokens);
        limiter.acquireMarketIndexChart(tokens);
        limiter.acquireStockRanking(tokens(scope("virtual", "other-key")));
    }

    @Test
    public void realAndVirtualUseOwnEndpointAndTokenInBothOrders() {
        KiwoomAccessTokenScope real = scope("real", "synthetic-key");
        cacheToken(real, "real-token");
        for (boolean realFirst : List.of(false, true)) {
            server.reset();
            KiwoomAccessTokenScope first = realFirst ? real : scope;
            KiwoomAccessTokenScope second = realFirst ? scope : real;
            expect(first, false, realFirst ? "real-token" : "synthetic-token").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
            expect(second, true, realFirst ? "synthetic-token" : "real-token").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
            provider(tokens(first)).getTradingValueRanks();
            provider(tokens(second)).getVolumeRanks();
            server.verify();
        }
    }

    @Test
    public void missingTokenIssuesOnlyWithinItsScopeThenQueriesRanking() {
        redis.values.clear();
        server.expect(requestTo(scope.getBaseUrl() + "/oauth2/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\":\"issued-token\",\"token_type\":\"bearer\",\"expires_dt\":\"20261024090000\",\"return_code\":0}", MediaType.APPLICATION_JSON));
        expect(scope, false, "issued-token").andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        assertTrue(query(false).isEmpty());
        assertEquals(List.of(scope.getLockKey()), locks.keys);
        server.verify();
    }

    @Test
    public void bothActualHttpAdaptersProduceSameCommonDtoAndExistingThreeMvcResponses() throws Exception {
        KisConfig config = new KisConfig() {
            @Override public String getBaseUrl() { return "https://kis.test"; }
        };
        config.appKey = "synthetic-key";
        config.appSecret = "synthetic-secret";
        config.accountNo = "synthetic-account";
        config.acntPrdtCd = "01";
        RestTemplate kisRest = new RestTemplate();
        MockRestServiceServer kisServer = MockRestServiceServer.bindTo(kisRest).build();
        KisAccessTokenRedisRepository kisTokens = new KisAccessTokenRedisRepository(redis);
        kisTokens.save(new KisAccessTokenScope(config.getBaseUrl(), config.appKey, config.appSecret), "kis-token", 86400);
        StockRankingProvider kis = new KisStockRankingProvider(new KisApiService(config, null, kisRest, kisTokens, null, null,
                new KisStockVolumeRankRedisRepository(redis, new ObjectMapper()), null, null,
                new RateLimitedKisApiClient(redis, config, new KisRestRateLimitConfig(18, 1)), com.antmillion.marketdata.CircuitBreakerTestSupport.defaults()));
        for (boolean volume : List.of(false, true)) {
            server.reset();
            kisServer.reset();
            String order = volume ? "0" : "3";
            String excluded = volume ? "0000001100" : "0000000000";
            kisServer.expect(requestTo(config.getBaseUrl() + "/uapi/domestic-stock/v1/quotations/volume-rank"
                    + "?FID_COND_MRKT_DIV_CODE=J&FID_COND_SCR_DIV_CODE=20171&FID_INPUT_ISCD=0000&FID_DIV_CLS_CODE=0"
                    + "&FID_BLNG_CLS_CODE=" + order + "&FID_TRGT_CLS_CODE=111111111&FID_TRGT_EXLS_CLS_CODE=" + excluded
                    + "&FID_INPUT_PRICE_1=0&FID_INPUT_PRICE_2=0&FID_VOL_CNT=0&FID_INPUT_DATE_1=0"))
                    .andExpect(method(HttpMethod.GET)).andExpect(header("tr_id", "FHPST01710000"))
                    .andRespond(withSuccess("{\"output\":[{\"hts_kor_isnm\":\"합성종목\",\"mksc_shrn_iscd\":\"005930\","
                            + "\"data_rank\":\"1\",\"stck_prpr\":\"71000\",\"acml_vol\":\"1234\",\"prdy_ctrt\":\"-1.20\"}]}", MediaType.APPLICATION_JSON));
            expect(volume).andRespond(withSuccess(body(row("005930", "-71000")), MediaType.APPLICATION_JSON));
            List<RankedStock> kisResult = volume ? kis.getVolumeRanks() : kis.getTradingValueRanks();
            List<RankedStock> result = query(volume);
            assertEquals(kisResult, result); // 합성 계약 비교이며 평균/당일 순위 일치를 입증하지 않습니다.
            assertMvcJson(kisResult, result, volume);
        }
        kisServer.verify();
        server.verify();
    }

    private void assertMvcJson(List<RankedStock> kisResult, List<RankedStock> result, boolean volume) throws Exception {
        StockRankingProvider kis = fixed(kisResult);
        StockRankingProvider kiwoom = fixed(result);
        MockMvc kisMvc = MockMvcBuilders.standaloneSetup(new KisController(null, null, null, null, null, kis)).build();
        MockMvc kiwoomMvc = MockMvcBuilders.standaloneSetup(new KisController(null, null, null, null, null, kiwoom)).build();
        ObjectMapper mapper = new ObjectMapper();
        if (volume) {
            String body = "{\"stockCodes\":[\"005930\"]}";
            JsonNode expected = mapper.readTree(kisMvc.perform(post("/api/kis/stocksByCode").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsByteArray());
            JsonNode actual = mapper.readTree(kiwoomMvc.perform(post("/api/kis/stocksByCode").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsByteArray());
            assertEquals(expected, actual);
            assertEquals(7, actual.get(0).size());
            assertTrue(actual.get(0).get("stck_prpr").isTextual());
        } else {
            for (String path : List.of("/api/kis/volumeRank", "/api/kis/volumeRank/paged?page=1&size=1")) {
                JsonNode expected = mapper.readTree(kisMvc.perform(get(path)).andReturn().getResponse().getContentAsByteArray());
                JsonNode actual = mapper.readTree(kiwoomMvc.perform(get(path)).andReturn().getResponse().getContentAsByteArray());
                assertEquals(expected, actual);
            }
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
            expect(false).andRespond(withSuccess(body(row("005930", "1")).replace("\"return_code\":0", "\"return_code\":0,\"return_msg\":\"synthetic-sensitive\""), MediaType.APPLICATION_JSON));
            query(false);
            assertTrue(output.toString().contains("Writing ["));
            for (String value : List.of("synthetic-token", "synthetic-key", "synthetic-secret", "synthetic-sensitive")) assertFalse(output.toString().contains(value));
            server.verify();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.close();
        }
    }

    private static StockRankingProvider fixed(List<RankedStock> result) {
        return new StockRankingProvider() {
            @Override public List<RankedStock> getTradingValueRanks() { return result; }
            @Override public List<RankedStock> getVolumeRanks() { return result; }
        };
    }

    private List<RankedStock> query(boolean volume) {
        return volume ? provider.getVolumeRanks() : provider.getTradingValueRanks();
    }

    private ResponseActions expect(boolean volume) { return expect(scope, volume, "synthetic-token"); }

    private ResponseActions expect(KiwoomAccessTokenScope target, boolean volume, String token) {
        return server.expect(requestTo(target.getBaseUrl() + "/api/dostk/rkinfo"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("api-id", "ka10030"))
                .andExpect(header("Authorization", "Bearer " + token))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(http -> {
                    assertNull(http.getHeaders().getFirst("cont-yn"));
                    assertNull(http.getHeaders().getFirst("next-key"));
                    JsonNode json = new ObjectMapper().readTree(((MockClientHttpRequest) http).getBodyAsString());
                    assertEquals(9, json.size());
                    assertEquals("000", json.get("mrkt_tp").asText());
                    assertEquals(volume ? "1" : "3", json.get("sort_tp").asText());
                    assertEquals(volume ? "16" : "0", json.get("mang_stk_incls").asText());
                    assertEquals("1", json.get("stex_tp").asText());
                    for (String field : List.of("crd_tp", "trde_qty_tp", "pric_tp", "trde_prica_tp", "mrkt_open_tp")) assertEquals("0", json.get(field).asText());
                });
    }

    private void assertSanitizedFailure(boolean volume) {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> query(volume));
        assertNull(error.getCause());
        for (String value : List.of("synthetic-sensitive", "synthetic-token", "synthetic-key", "synthetic-secret")) assertFalse(error.toString().contains(value));
    }

    private StockRankingProvider provider(KiwoomTokenService target) {
        // 기존 adapter 계약은 저장 실패 대역으로 항상 miss를 만들어 검증합니다.
        KiwoomTokenServiceTest.MemoryRedis cacheRedis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        cacheRedis.failWrites = true;
        return new KiwoomStockRankingProvider(target, rest, limiter,
                new KiwoomStockRankingRedisRepository(cacheRedis, clock));
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
        return "{\"return_code\":0,\"tdy_trde_qty_upper\":[" + String.join(",", rows) + "]}";
    }

    private static String row(String code, String price) {
        return "{\"stk_nm\":\"합성종목\",\"stk_cd\":\"" + code + "\",\"cur_prc\":\"" + price + "\",\"trde_qty\":\"1234\",\"flu_rt\":\"-1.20\"}";
    }

    private static RankedStock stock(String code, String rank, String price) {
        return new RankedStock("합성종목", code, rank, price, "1234", "-1.20", false);
    }
}
