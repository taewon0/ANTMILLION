package com.antmillion.kiwoom;

import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomPeriodChartRedisRepository;
import com.antmillion.kiwoom.service.KiwoomPeriodChartProvider;
import com.antmillion.kiwoom.service.KiwoomRestRateLimitTest;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.marketdata.dto.PeriodCandle;
import com.antmillion.marketdata.dto.PeriodChartRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bucket4j.TimeMeter;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 개발 설정·DB·실제 Redis/증권사 없이 고정 시각과 메모리/HTTP 대역으로 검증합니다. */
public class KiwoomPeriodChartCacheTest {
    private static final String REAL = "https://api.kiwoom.com";
    private static final String VIRTUAL = "https://mockapi.kiwoom.com";
    private KiwoomTokenServiceTest.MutableClock clock;
    private KiwoomTokenServiceTest.MemoryRedis redis;
    private KiwoomTokenServiceTest.MemoryRedis tokenRedis;
    private KiwoomPeriodChartRedisRepository cache;
    private KiwoomAccessTokenRedisRepository tokenCache;
    private KiwoomAccessTokenScope scope;
    private KiwoomTokenService tokens;
    private KiwoomRestRateLimiter limiter;
    private KiwoomPeriodChartProvider provider;
    private RestTemplate rest;
    private MockRestServiceServer server;

    @Before
    public void setUp() {
        clock = new KiwoomTokenServiceTest.MutableClock();
        redis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        tokenRedis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        cache = new KiwoomPeriodChartRedisRepository(redis, clock);
        tokenCache = new KiwoomAccessTokenRedisRepository(tokenRedis, clock);
        scope = new KiwoomAccessTokenScope("virtual", "synthetic-key", "synthetic-secret");
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        tokens = tokens(scope);
        tokenCache.save(scope, "synthetic-token", clock.instant().plusSeconds(200_000), 600);
        limiter = KiwoomRestRateLimitTest.withClock(new TimeMeter() {
            @Override public long currentTimeNanos() { return 0; }
            @Override public boolean isWallClockBased() { return false; }
        });
        provider = new KiwoomPeriodChartProvider(tokens, rest, limiter, cache);
    }

    @Test
    public void twentyFourHourTtlAndReadDoNotExtendExpiration() {
        cache.save(VIRTUAL, request(), candles());
        String key = onlyKey();
        assertEquals(Long.valueOf(86_400_000), redis.ttls.get(key));
        long expiration = redis.expirations.get(key);
        clock.advanceSeconds(86_399);
        assertEquals(candles(), cache.find(VIRTUAL, request()).orElseThrow());
        assertEquals(Long.valueOf(expiration), redis.expirations.get(key));
        assertEquals(1, redis.writes.get());
        clock.advanceSeconds(1);
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
    }

    @Test
    public void absoluteExpiryStillAppliesWithoutRedisTtl() {
        cache.save(VIRTUAL, request(), candles());
        redis.expirations.clear();
        clock.advanceSeconds(86_400);
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        assertEquals(1, redis.values.size());
    }

    @Test
    public void clockBeforeSaveTimeRejectsFutureCache() {
        cache.save(VIRTUAL, request(), candles());
        clock.advanceSeconds(-1);
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
    }

    @Test
    public void environmentStockPeriodAndBothDateBoundsAreSeparatedInBothOrders() {
        List<PeriodChartRequest> requests = List.of(request(),
                new PeriodChartRequest("000660", "D", start(), end()),
                new PeriodChartRequest("005930", "W", start(), end()),
                new PeriodChartRequest("005930", "M", start(), end()),
                new PeriodChartRequest("005930", "Y", start(), end()),
                new PeriodChartRequest("005930", "D", start().plusDays(1), end()),
                new PeriodChartRequest("005930", "D", start(), end().plusDays(1)));
        for (List<String> environments : List.of(List.of(REAL, VIRTUAL), List.of(VIRTUAL, REAL))) {
            setUp();
            int number = 0;
            Map<String, List<PeriodCandle>> expected = new HashMap<>();
            for (String environment : environments) {
                for (PeriodChartRequest query : requests) {
                    List<PeriodCandle> result = List.of(candle(Integer.toString(++number)));
                    cache.save(environment, query, result);
                    expected.put(environment + query, result);
                }
            }
            assertEquals(14, redis.values.size());
            for (String environment : environments) {
                for (PeriodChartRequest query : requests) {
                    assertEquals(expected.get(environment + query), cache.find(environment, query).orElseThrow());
                }
            }
        }
    }

    @Test
    public void providerMarketAdjustmentAndLegacyKeysAreNeverReusedOrChanged() {
        cache.save(VIRTUAL, request(), candles());
        String key = onlyKey();
        assertTrue(key.startsWith("kiwoom:chart:period:v1:"));
        assertTrue(key.contains(":3:KRX:"));
        assertTrue(key.endsWith(":1:1"));
        assertFalse(key.contains("synthetic"));
        Object saved = redis.values.remove(key);
        Map<String, Object> unrelated = new HashMap<>();
        unrelated.put(key.replace("kiwoom:", "kis:"), saved);
        unrelated.put(key.replace(":3:KRX:", ":3:NXT:"), saved);
        unrelated.put(key.substring(0, key.length() - 1) + "0", saved);
        unrelated.put("chart:005930:D", saved);
        unrelated.put("kiwoom:price:single:v1:legacy", "0");
        redis.values.putAll(unrelated);
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        assertEquals(List.of(key), redis.reads);
        assertEquals(unrelated, redis.values);
    }

    @Test
    public void pricesNullRowsDuplicatesEmptyValuesAndOrderSurviveSerialization() {
        PeriodCandle unusual = new PeriodCandle("20261022", "999999999999999999999999", "", null, "0");
        List<PeriodCandle> result = Arrays.asList(candle("100"), null, candle("100"), unusual);
        cache.save(VIRTUAL, request(), result);
        ObjectMapper mapper = new ObjectMapper();
        mapper.activateDefaultTyping(mapper.getPolymorphicTypeValidator(), ObjectMapper.DefaultTyping.NON_FINAL);
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);
        redis.values.replaceAll((key, value) -> serializer.deserialize(serializer.serialize(value)));
        assertEquals(result, cache.find(VIRTUAL, request()).orElseThrow());
    }

    @Test
    public void emptyResultIsAHitAndNullListIsNotSaved() {
        cache.save(VIRTUAL, request(), null);
        assertTrue(redis.values.isEmpty());
        cache.save(VIRTUAL, request(), List.of());
        assertTrue(cache.find(VIRTUAL, request()).orElseThrow().isEmpty());
    }

    @Test
    public void callerListMutationsCannotChangeStoredSnapshot() {
        List<PeriodCandle> source = new ArrayList<>(candles());
        cache.save(VIRTUAL, request(), source);
        source.clear();
        cache.find(VIRTUAL, request()).orElseThrow().clear();
        assertEquals(candles(), cache.find(VIRTUAL, request()).orElseThrow());
    }

    @Test
    public void malformedEnvelopeTypesAndOverflowExpiryAreMisses() throws Exception {
        cache.save(VIRTUAL, request(), candles());
        String key = onlyKey();
        for (Object value : List.of(100, "sensitive-invalid-json", "null", "[]", "{}",
                "{\"expires_at\":\"1\",\"candles\":[]}",
                "{\"expires_at\":9223372036854775808,\"candles\":[]}",
                "{\"expires_at\":-9223372036854775808,\"candles\":[]}", envelope("null"), envelope("{}"))) {
            redis.values.put(key, value);
            assertFalse(cache.find(VIRTUAL, request()).isPresent());
        }
    }

    @Test
    public void invalidDatesOrderMissingFieldsAndNoncanonicalPricesAreMisses() throws Exception {
        cache.save(VIRTUAL, request(), candles());
        String key = onlyKey();
        ObjectMapper mapper = new ObjectMapper();
        String valid = mapper.readTree((String) redis.values.get(key)).path("candles").get(0).toString();
        for (String field : List.of("date", "close", "open", "high", "low")) {
            ObjectNode row = (ObjectNode) mapper.readTree(valid);
            row.remove(field);
            redis.values.put(key, envelope("[" + row + "]"));
            assertFalse(cache.find(VIRTUAL, request()).isPresent());
        }
        for (String date : List.of("20260230", "20261020", "20261024", "2026-10-23", "")) {
            ObjectNode row = (ObjectNode) mapper.readTree(valid);
            row.put("date", date);
            redis.values.put(key, envelope("[" + row + "]"));
            assertFalse(cache.find(VIRTUAL, request()).isPresent());
        }
        for (String price : List.of("-100", "+100", "01", "1.5", "bad-sensitive")) {
            ObjectNode row = (ObjectNode) mapper.readTree(valid);
            row.put("close", price);
            redis.values.put(key, envelope("[" + row + "]"));
            assertFalse(cache.find(VIRTUAL, request()).isPresent());
        }
        ObjectNode row = (ObjectNode) mapper.readTree(valid);
        row.put("close", 100);
        redis.values.put(key, envelope("[" + row + "]"));
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        redis.values.put(key, envelope("[" + valid.replace("20261023", "20261022") + ",null," + valid + "]"));
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        redis.values.put(key, envelope("[" + String.join(",", java.util.Collections.nCopies(101, valid)) + "]"));
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
    }

    @Test
    public void redisFailureIsMissAndWriteFailureDoesNotEscape() {
        redis.failWrites = true;
        cache.save(VIRTUAL, request(), candles());
        assertTrue(redis.values.isEmpty());
        redis.failReads = true;
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
    }

    @Test
    public void warmHitNeedsNoTokenAndLeavesFullRequestBudgetAvailable() {
        cache.save(VIRTUAL, request(), candles());
        tokenRedis.values.clear();
        assertEquals(candles(), provider.getPeriodChart(request()));
        assertTrue(tokenRedis.reads.isEmpty());
        tokenCache.save(scope, "synthetic-token", clock.instant().plusSeconds(200_000), 600);
        PeriodChartRequest other = new PeriodChartRequest("000660", "D", start(), end());
        expectChart("000660", body("000660", "100"));
        assertEquals(candles(), provider.getPeriodChart(other));
        assertEquals(candles(), provider.getPeriodChart(request()));
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getPeriodChart(
                new PeriodChartRequest("035420", "D", start(), end())));
        assertEquals(1, tokenRedis.reads.size());
        server.verify();
    }

    @Test
    public void successfulHttpIsCachedAndReusableWhileBudgetIsExhausted() {
        expectChart("005930", body("005930", "+00100"));
        assertEquals(candles(), provider.getPeriodChart(request()));
        tokenRedis.failReads = true;
        assertEquals(candles(), provider.getPeriodChart(request()));
        assertEquals(1, tokenRedis.reads.size());
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void successfulEmptyHttpResultIsCached() {
        expectChart("005930", "{\"return_code\":0,\"stk_cd\":\"005930\",\"stk_dt_pole_chart_qry\":[]}");
        assertTrue(provider.getPeriodChart(request()).isEmpty());
        assertTrue(provider.getPeriodChart(request()).isEmpty());
        assertEquals(1, tokenRedis.reads.size());
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void allPeriodsKeepLatestHundredCandlesAndNullSemanticsOnWarmReads() {
        String[] periods = {"D", "W", "M", "Y"};
        String[] ids = {"ka10081", "ka10082", "ka10083", "ka10094"};
        String[] fields = {"stk_dt_pole_chart_qry", "stk_stk_pole_chart_qry",
                "stk_mth_pole_chart_qry", "stk_yr_pole_chart_qry"};
        String raw = "{\"dt\":\"20261023\",\"cur_prc\":\"-00100\",\"open_pric\":\"\","
                + "\"high_pric\":null,\"low_pric\":\"999999999999999999999999\"}";
        for (int i = 0; i < periods.length; i++) {
            setUp();
            PeriodChartRequest query = new PeriodChartRequest("005930", periods[i], start(), end());
            String response = "{\"return_code\":0,\"" + fields[i] + "\":[null,"
                    + String.join(",", java.util.Collections.nCopies(100, raw)) + "]}";
            server.expect(requestTo(VIRTUAL + "/api/dostk/chart")).andExpect(header("api-id", ids[i]))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            List<PeriodCandle> cold = provider.getPeriodChart(query);
            assertEquals(100, cold.size());
            assertNull(cold.get(0));
            assertEquals(new PeriodCandle("20261023", "100", "", null, "999999999999999999999999"), cold.get(1));
            assertEquals(cold, provider.getPeriodChart(query));
            assertEquals(1, tokenRedis.reads.size());
            assertEquals(1, redis.writes.get());
            server.verify();
        }
    }

    @Test
    public void expiryRequeriesHttpAndRefreshesOnlyAfterSuccess() {
        cache.save(VIRTUAL, request(), List.of(candle("99")));
        redis.expirations.clear();
        clock.advanceSeconds(86_400);
        expectChart("005930", body("005930", "100"));
        assertEquals(candles(), provider.getPeriodChart(request()));
        assertEquals(candles(), provider.getPeriodChart(request()));
        assertEquals(2, redis.writes.get());
        server.verify();
    }

    @Test
    public void corruptCacheIsReplacedByNormalHttpResult() {
        cache.save(VIRTUAL, request(), candles());
        redis.values.put(onlyKey(), "sensitive-invalid-json");
        expectChart("005930", body("005930", "101"));
        assertEquals(List.of(candle("101")), provider.getPeriodChart(request()));
        assertEquals(List.of(candle("101")), provider.getPeriodChart(request()));
        server.verify();
    }

    @Test
    public void cacheReadOrWriteFailurePreservesNormalHttpResponse() {
        for (boolean failRead : List.of(true, false)) {
            setUp();
            redis.failReads = failRead;
            redis.failWrites = !failRead;
            expectChart("005930", body("005930", "100"));
            assertEquals(candles(), provider.getPeriodChart(request()));
            server.verify();
        }
    }

    @Test
    public void differentCredentialsSharePublicChartCacheWithoutSharingToken() {
        cache.save(VIRTUAL, request(), candles());
        KiwoomAccessTokenScope rotated = new KiwoomAccessTokenScope("virtual", "other-key", "other-secret");
        assertEquals(candles(), new KiwoomPeriodChartProvider(tokens(rotated), rest, limiter, cache)
                .getPeriodChart(request()));
        assertTrue(tokenRedis.reads.isEmpty());
        assertEquals(1, redis.values.size());
    }

    @Test
    public void cacheMissAtExhaustedBudgetNeverUsesTokenOrHttp() {
        limiter.acquirePeriodChart(tokens, "ka10081");
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getPeriodChart(request()));
        assertTrue(tokenRedis.reads.isEmpty());
        assertTrue(redis.values.isEmpty());
        server.verify();
    }

    @Test
    public void invalidRequestsAreRejectedBeforeAnyCacheOrTokenLookup() {
        for (PeriodChartRequest invalid : Arrays.asList(null,
                new PeriodChartRequest("005930_NX", "D", start(), end()),
                new PeriodChartRequest("005930", "X", start(), end()),
                new PeriodChartRequest("005930", "D", end(), start()))) {
            assertThrows(IllegalArgumentException.class, () -> provider.getPeriodChart(invalid));
        }
        assertTrue(redis.reads.isEmpty());
        assertTrue(tokenRedis.reads.isEmpty());
    }

    @Test
    public void httpBusinessAndNormalizationFailuresAreNotCached() {
        List<String> failures = List.of("{\"return_code\":1,\"return_msg\":\"sensitive\"}",
                body("005930", "1.5"), "{\"return_code\":0}");
        for (String failure : failures) {
            setUp();
            expectChart("005930", failure);
            assertThrows(IllegalStateException.class, () -> provider.getPeriodChart(request()));
            assertTrue(redis.values.isEmpty());
            assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getPeriodChart(request()));
            server.verify();
        }
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR)) {
            setUp();
            server.expect(requestTo(VIRTUAL + "/api/dostk/chart")).andRespond(withStatus(status));
            assertThrows(IllegalStateException.class, () -> provider.getPeriodChart(request()));
            assertTrue(redis.values.isEmpty());
            server.verify();
        }
    }

    private KiwoomTokenService tokens(KiwoomAccessTokenScope tokenScope) {
        return new KiwoomTokenService(tokenScope, 600, rest, tokenCache,
                new KiwoomTokenServiceTest.LockClient().client(), clock);
    }

    private void expectChart(String stockCode, String response) {
        server.expect(requestTo(VIRTUAL + "/api/dostk/chart")).andExpect(method(HttpMethod.POST))
                .andExpect(header("api-id", "ka10081"))
                .andExpect(header("Authorization", "Bearer synthetic-token"))
                .andExpect(request -> {
                    com.fasterxml.jackson.databind.JsonNode body = new ObjectMapper().readTree(
                            ((MockClientHttpRequest) request).getBodyAsString());
                    assertEquals(stockCode, body.path("stk_cd").asText());
                    assertEquals("20261023", body.path("base_dt").asText());
                    assertEquals("1", body.path("upd_stkpc_tp").asText());
                    assertEquals(3, body.size());
                })
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private String envelope(String candlesJson) {
        return "{\"expires_at\":" + (clock.millis() + 86_400_000) + ",\"candles\":" + candlesJson + "}";
    }

    private String onlyKey() {
        assertEquals(1, redis.values.size());
        return redis.values.keySet().iterator().next();
    }

    private static String body(String stockCode, String price) {
        return "{\"return_code\":0,\"stk_cd\":\"" + stockCode + "\",\"stk_dt_pole_chart_qry\":[{"
                + "\"dt\":\"20261023\",\"cur_prc\":\"" + price + "\",\"open_pric\":\"100\","
                + "\"high_pric\":\"100\",\"low_pric\":\"100\"}]}";
    }

    private static LocalDate start() { return LocalDate.of(2026, 10, 21); }
    private static LocalDate end() { return LocalDate.of(2026, 10, 23); }
    private static PeriodChartRequest request() { return new PeriodChartRequest("005930", "D", start(), end()); }
    private static PeriodCandle candle(String price) { return new PeriodCandle("20261023", price, "100", "100", "100"); }
    private static List<PeriodCandle> candles() { return List.of(candle("100")); }
}
