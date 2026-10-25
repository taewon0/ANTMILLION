package com.antmillion.kiwoom;

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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 개발 설정·DB·실제 Redis/증권사 없이 고정 시각과 메모리/HTTP 대역으로 검증합니다. */
public class KiwoomDailyMinuteCacheTest {
    private static final String REAL = "https://api.kiwoom.com";
    private static final String VIRTUAL = "https://mockapi.kiwoom.com";
    private KiwoomTokenServiceTest.MutableClock clock;
    private KiwoomTokenServiceTest.MemoryRedis redis;
    private KiwoomTokenServiceTest.MemoryRedis tokenRedis;
    private KiwoomDailyMinuteRedisRepository cache;
    private KiwoomAccessTokenRedisRepository tokenCache;
    private KiwoomAccessTokenScope scope;
    private KiwoomTokenService tokens;
    private KiwoomRestRateLimiter limiter;
    private KiwoomMinuteChartProvider provider;
    private RestTemplate rest;
    private MockRestServiceServer server;

    @Before
    public void setUp() {
        clock = new KiwoomTokenServiceTest.MutableClock();
        redis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        tokenRedis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        cache = new KiwoomDailyMinuteRedisRepository(redis, clock);
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
        provider = new KiwoomMinuteChartProvider(tokens, rest, limiter, cache, com.antmillion.marketdata.CircuitBreakerTestSupport.defaults());
    }

    @Test
    public void sixtySecondTtlAndReadDoNotExtendExpiration() {
        cache.save(VIRTUAL, request(), candles());
        String key = onlyKey();
        assertEquals(Long.valueOf(60_000), redis.ttls.get(key));
        long expiration = redis.expirations.get(key);
        clock.advanceSeconds(59);
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
        clock.advanceSeconds(60);
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
    public void environmentStockAndDateAreSeparatedInBothOrders() {
        List<MinuteChartRequest> requests = List.of(request(),
                new MinuteChartRequest("000660", end()),
                new MinuteChartRequest("005930", end().minusDays(1)));
        for (List<String> environments : List.of(List.of(REAL, VIRTUAL), List.of(VIRTUAL, REAL))) {
            setUp();
            int number = 0;
            Map<String, List<MinuteCandle>> expected = new HashMap<>();
            for (String environment : environments) {
                for (MinuteChartRequest query : requests) {
                    List<MinuteCandle> result = List.of(new MinuteCandle(query.getDate().format(
                            DateTimeFormatter.BASIC_ISO_DATE), "153000",
                            Integer.toString(++number), "100", "100", "100"));
                    cache.save(environment, query, result);
                    expected.put(environment + query, result);
                }
            }
            assertEquals(6, redis.values.size());
            for (String environment : environments) {
                for (MinuteChartRequest query : requests) {
                    assertEquals(expected.get(environment + query), cache.find(environment, query).orElseThrow());
                }
            }
        }
    }

    @Test
    public void providerMarketAdjustmentAndLegacyKeysAreNeverReusedOrChanged() {
        cache.save(VIRTUAL, request(), candles());
        String key = onlyKey();
        assertTrue(key.startsWith("kiwoom:chart:daily-minute:v2:"));
        assertTrue(key.contains(":3:KRX:"));
        assertTrue(key.endsWith(":1:1:1:1:6:153000"));
        assertFalse(key.contains("synthetic"));
        Object saved = redis.values.remove(key);
        Map<String, Object> unrelated = new HashMap<>();
        unrelated.put(key.replace("kiwoom:", "kis:"), saved);
        unrelated.put(key.replace(":v2:", ":v1:"), envelope("[]"));
        unrelated.put(key.replace(":3:KRX:", ":3:NXT:"), saved);
        unrelated.put(key.replace(":1:1:1:1:6:", ":1:1:1:0:6:"), saved);
        unrelated.put(key.replace(":1:1:1:1:6:", ":1:5:1:1:6:"), saved);
        unrelated.put(key.replace(":6:153000", ":6:152900"), saved);
        unrelated.put("stream:minute:20261023:005930", saved);
        unrelated.put("kiwoom:price:single:v1:legacy", "0");
        redis.values.putAll(unrelated);
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        assertEquals(List.of(key), redis.reads);
        assertEquals(unrelated, redis.values);
    }

    @Test
    public void pricesNullRowsDuplicatesEmptyValuesAndOrderSurviveSerialization() {
        MinuteCandle unusual = new MinuteCandle("20261023", "153000", "999999999999999999999999", "", null, "0");
        List<MinuteCandle> result = Arrays.asList(candle("100"), null, candle("100"), unusual);
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
        List<MinuteCandle> source = new ArrayList<>(candles());
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
    public void invalidDatesTimesOrderMissingFieldsAndNoncanonicalPricesAreMisses() throws Exception {
        cache.save(VIRTUAL, request(), candles());
        String key = onlyKey();
        ObjectMapper mapper = new ObjectMapper();
        String valid = mapper.readTree((String) redis.values.get(key)).path("candles").get(0).toString();
        for (String field : List.of("date", "time", "close", "open", "high", "low")) {
            ObjectNode row = (ObjectNode) mapper.readTree(valid);
            row.remove(field);
            redis.values.put(key, envelope("[" + row + "]"));
            assertFalse(cache.find(VIRTUAL, request()).isPresent());
        }
        for (String date : List.of("20260230", "00001022", "20261024", "2026-10-23", "")) {
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
        for (String time : List.of("153001", "240000", "126000", "15:30:00", "", "15300")) {
            ObjectNode invalidTime = (ObjectNode) mapper.readTree(valid);
            invalidTime.put("time", time);
            redis.values.put(key, envelope("[" + invalidTime + "]"));
            assertFalse(cache.find(VIRTUAL, request()).isPresent());
        }
        ObjectNode row = (ObjectNode) mapper.readTree(valid);
        row.put("close", 100);
        redis.values.put(key, envelope("[" + row + "]"));
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        redis.values.put(key, envelope("[" + valid + ",null," + valid.replace("153000", "152900") + "]"));
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        redis.values.put(key, envelope("[" + String.join(",", java.util.Collections.nCopies(121, valid)) + "]"));
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
        assertEquals(candles(), provider.getDailyMinutes(request()));
        assertTrue(tokenRedis.reads.isEmpty());
        tokenCache.save(scope, "synthetic-token", clock.instant().plusSeconds(200_000), 600);
        MinuteChartRequest other = new MinuteChartRequest("000660", end());
        expectChart("000660", body("000660", "100"));
        assertEquals(candles(), provider.getDailyMinutes(other));
        assertEquals(candles(), provider.getDailyMinutes(request()));
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getDailyMinutes(
                new MinuteChartRequest("035420", end())));
        assertEquals(1, tokenRedis.reads.size());
        server.verify();
    }

    @Test
    public void successfulHttpIsCachedAndReusableWhileBudgetIsExhausted() {
        expectChart("005930", body("005930", "+00100"));
        assertEquals(candles(), provider.getDailyMinutes(request()));
        tokenRedis.failReads = true;
        assertEquals(candles(), provider.getDailyMinutes(request()));
        assertEquals(1, tokenRedis.reads.size());
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void successfulEmptyHttpResultIsCached() {
        expectChart("005930", "{\"return_code\":0,\"stk_cd\":\"005930\",\"stk_min_pole_chart_qry\":[]}");
        assertTrue(provider.getDailyMinutes(request()).isEmpty());
        assertTrue(provider.getDailyMinutes(request()).isEmpty());
        assertEquals(1, tokenRedis.reads.size());
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void coldAndWarmReadsKeepLatest120NullRowsAndStringPrices() {
        String raw = "{\"cntr_tm\":\"20261023153000\",\"cur_prc\":\"-00100\",\"open_pric\":\"\","
                + "\"high_pric\":null,\"low_pric\":\"999999999999999999999999\"}";
        String response = "{\"return_code\":0,\"stk_min_pole_chart_qry\":["
                + String.join(",", java.util.Collections.nCopies(119, raw)) + ",null," + raw + "]}";
        expectChart("005930", response);
        List<MinuteCandle> cold = provider.getDailyMinutes(request());
        assertEquals(120, cold.size());
        assertNull(cold.get(0));
        assertEquals(new MinuteCandle("20261023", "153000", "100", "", null,
                "999999999999999999999999"), cold.get(1));
        assertEquals(cold, provider.getDailyMinutes(request()));
        assertEquals(1, tokenRedis.reads.size());
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void expiryRequeriesHttpAndRefreshesOnlyAfterSuccess() {
        cache.save(VIRTUAL, request(), List.of(candle("99")));
        redis.expirations.clear();
        clock.advanceSeconds(60);
        expectChart("005930", body("005930", "100"));
        assertEquals(candles(), provider.getDailyMinutes(request()));
        assertEquals(candles(), provider.getDailyMinutes(request()));
        assertEquals(2, redis.writes.get());
        server.verify();
    }

    @Test
    public void corruptCacheIsReplacedByNormalHttpResult() {
        cache.save(VIRTUAL, request(), candles());
        redis.values.put(onlyKey(), "sensitive-invalid-json");
        expectChart("005930", body("005930", "101"));
        assertEquals(List.of(candle("101")), provider.getDailyMinutes(request()));
        assertEquals(List.of(candle("101")), provider.getDailyMinutes(request()));
        server.verify();
    }

    @Test
    public void cacheReadOrWriteFailurePreservesNormalHttpResponse() {
        for (boolean failRead : List.of(true, false)) {
            setUp();
            redis.failReads = failRead;
            redis.failWrites = !failRead;
            expectChart("005930", body("005930", "100"));
            assertEquals(candles(), provider.getDailyMinutes(request()));
            server.verify();
        }
    }

    @Test
    public void differentCredentialsSharePublicChartCacheWithoutSharingToken() {
        cache.save(VIRTUAL, request(), candles());
        KiwoomAccessTokenScope rotated = new KiwoomAccessTokenScope("virtual", "other-key", "other-secret");
        assertEquals(candles(), new KiwoomMinuteChartProvider(tokens(rotated), rest, limiter, cache, com.antmillion.marketdata.CircuitBreakerTestSupport.defaults())
                .getDailyMinutes(request()));
        assertTrue(tokenRedis.reads.isEmpty());
        assertEquals(1, redis.values.size());
    }

    @Test
    public void cacheMissAtExhaustedBudgetNeverUsesTokenOrHttp() {
        limiter.acquireMinuteChart(tokens);
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getDailyMinutes(request()));
        assertTrue(tokenRedis.reads.isEmpty());
        assertTrue(redis.values.isEmpty());
        server.verify();
    }

    @Test
    public void invalidRequestsAreRejectedBeforeAnyCacheOrTokenLookup() {
        for (MinuteChartRequest invalid : Arrays.asList(null,
                new MinuteChartRequest("005930_NX", end()),
                new MinuteChartRequest("005930", null),
                new MinuteChartRequest("005930", LocalDate.of(0, 1, 1)))) {
            assertThrows(IllegalArgumentException.class, () -> provider.getDailyMinutes(invalid));
        }
        assertTrue(redis.reads.isEmpty());
        assertTrue(tokenRedis.reads.isEmpty());
    }

    @Test
    public void httpBusinessAndNormalizationFailuresAreNotCached() {
        List<String> failures = List.of("{\"return_code\":1,\"return_msg\":\"sensitive\"}",
                body("005930", "1.5"), body("000660", "100"),
                body("005930", "100").replace("20261023153000", "20260230153000"));
        for (String failure : failures) {
            setUp();
            expectChart("005930", failure);
            assertThrows(IllegalStateException.class, () -> provider.getDailyMinutes(request()));
            assertTrue(redis.values.isEmpty());
            assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getDailyMinutes(request()));
            server.verify();
        }
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR)) {
            setUp();
            server.expect(requestTo(VIRTUAL + "/api/dostk/chart")).andRespond(withStatus(status));
            assertThrows(IllegalStateException.class, () -> provider.getDailyMinutes(request()));
            assertTrue(redis.values.isEmpty());
            server.verify();
        }
    }

    @Test
    public void recentAlwaysUsesHttpAndBudgetEvenWhenDailyCacheIsWarm() {
        cache.save(VIRTUAL, request(), candles());
        AtomicLong nanos = new AtomicLong();
        KiwoomRestRateLimiter advancing = KiwoomRestRateLimitTest.withClock(new TimeMeter() {
            @Override public long currentTimeNanos() { return nanos.get(); }
            @Override public boolean isWallClockBased() { return false; }
        });
        KiwoomMinuteChartProvider query = new KiwoomMinuteChartProvider(tokens, rest, advancing, cache,
                com.antmillion.marketdata.CircuitBreakerTestSupport.defaults(),
                Clock.fixed(Instant.parse("2026-10-23T06:30:00Z"), ZoneId.of("Asia/Seoul")));
        expectChart("005930", body("005930", "100"));
        expectChart("005930", body("005930", "101"));
        assertEquals(100, query.getRecentMinutes("005930").get(0).getClosePrice());
        assertThrows(KiwoomRateLimitExceededException.class, () -> query.getRecentMinutes("005930"));
        nanos.addAndGet(1_000_000_000L);
        assertEquals(101, query.getRecentMinutes("005930").get(0).getClosePrice());
        assertTrue(redis.reads.isEmpty());
        assertEquals(1, redis.writes.get());
        assertEquals(2, tokenRedis.reads.size());
        assertEquals(candles(), query.getDailyMinutes(request()));
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void missingListKeepsEmptyResponseWithoutSavingAnEntry() {
        for (String response : List.of("{\"return_code\":0}", "{\"stk_min_pole_chart_qry\":null}")) {
            setUp();
            expectChart("005930", response);
            assertTrue(provider.getDailyMinutes(request()).isEmpty());
            assertTrue(redis.values.isEmpty());
            server.verify();
        }
    }

    @Test
    public void tokenFailureIsNotCachedAndDoesNotRefundBudget() {
        tokenRedis.failReads = true;
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.getDailyMinutes(request()));
        assertNull(error.getCause());
        assertTrue(redis.values.isEmpty());
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getDailyMinutes(request()));
        server.verify();
    }

    @Test
    public void onlyFilteredAndNormalizedAscendingCandlesAreSaved() {
        String current = body("005930", "-00100");
        String row = current.substring(current.indexOf("[{") + 1, current.lastIndexOf(']'));
        String response = current.replace(row, row.replace("153000", "153100") + "," + row + ","
                + row.replace("153000", "152900") + "," + row.replace("20261023", "20261022"));
        expectChart("005930", response);
        List<MinuteCandle> expected = List.of(new MinuteCandle("20261022", "153000", "100", "100", "100", "100"),
                new MinuteCandle("20261023", "152900", "100", "100", "100", "100"),
                candle("100"));
        assertEquals(expected, provider.getDailyMinutes(request()));
        assertEquals(expected, provider.getDailyMinutes(request()));
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void holidayColdAndWarmReadsPreservePreviousDatesAndIgnoreOldEmptyCache() {
        MinuteChartRequest holiday = new MinuteChartRequest("005930", end().plusDays(1));
        cache.save(VIRTUAL, holiday, List.of());
        String key = onlyKey();
        String legacyKey = key.replace(":v2:", ":v1:");
        Object legacyEmpty = redis.values.remove(key);
        redis.values.put(legacyKey, legacyEmpty);
        String response = body("005930", "-00100");
        String row = response.substring(response.indexOf("[{") + 1, response.lastIndexOf(']'));
        response = response.replace(row, row + ",null," + row.replace("20261023", "20261022"));
        expectChart("005930", "20261024", response);
        List<MinuteCandle> expected = Arrays.asList(
                new MinuteCandle("20261022", "153000", "100", "100", "100", "100"), null, candle("100"));
        assertEquals(expected, provider.getDailyMinutes(holiday));
        tokenRedis.failReads = true;
        assertEquals(expected, provider.getDailyMinutes(holiday));
        assertEquals(1, tokenRedis.reads.size());
        assertEquals(legacyEmpty, redis.values.get(legacyKey));
        assertEquals(Long.valueOf(60_000), redis.ttls.get(key));
        assertEquals(2, redis.writes.get());
        server.verify();
    }

    @Test
    public void cacheOrderUsesFullDatesAcrossDifferentTradingDays() {
        MinuteCandle earlier = new MinuteCandle("20261022", "153000", "100", "100", "100", "100");
        MinuteCandle later = new MinuteCandle("20261023", "090000", "101", "100", "100", "100");
        List<MinuteCandle> valid = Arrays.asList(earlier, null, later);
        cache.save(VIRTUAL, request(), valid);
        assertEquals(valid, cache.find(VIRTUAL, request()).orElseThrow());
        cache.save(VIRTUAL, request(), List.of(later, earlier));
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        cache.save(VIRTUAL, request(), List.of(new MinuteCandle("20261022", "153001", "100", "100", "100", "100")));
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
    }

    @Test
    public void expiredEntryIsNotReturnedOnRemoteFailure() {
        cache.save(VIRTUAL, request(), candles());
        redis.expirations.clear();
        clock.advanceSeconds(60);
        server.expect(requestTo(VIRTUAL + "/api/dostk/chart"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThrows(IllegalStateException.class, () -> provider.getDailyMinutes(request()));
        assertEquals(1, redis.writes.get());
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        server.verify();
    }

    private KiwoomTokenService tokens(KiwoomAccessTokenScope tokenScope) {
        return new KiwoomTokenService(tokenScope, 600, rest, tokenCache,
                new KiwoomTokenServiceTest.LockClient().client(), clock);
    }

    private void expectChart(String stockCode, String response) {
        expectChart(stockCode, "20261023", response);
    }

    private void expectChart(String stockCode, String baseDate, String response) {
        server.expect(requestTo(VIRTUAL + "/api/dostk/chart")).andExpect(method(HttpMethod.POST))
                .andExpect(header("api-id", "ka10080"))
                .andExpect(header("Authorization", "Bearer synthetic-token"))
                .andExpect(request -> {
                    com.fasterxml.jackson.databind.JsonNode body = new ObjectMapper().readTree(
                            ((MockClientHttpRequest) request).getBodyAsString());
                    assertEquals(stockCode, body.path("stk_cd").asText());
                    assertEquals(baseDate, body.path("base_dt").asText());
                    assertEquals("1", body.path("upd_stkpc_tp").asText());
                    assertEquals("1", body.path("tic_scope").asText());
                    assertEquals(4, body.size());
                })
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private String envelope(String candlesJson) {
        return "{\"expires_at\":" + (clock.millis() + 60_000) + ",\"candles\":" + candlesJson + "}";
    }

    private String onlyKey() {
        assertEquals(1, redis.values.size());
        return redis.values.keySet().iterator().next();
    }

    private static String body(String stockCode, String price) {
        return "{\"return_code\":0,\"stk_cd\":\"" + stockCode + "\",\"stk_min_pole_chart_qry\":[{"
                + "\"cntr_tm\":\"20261023153000\",\"cur_prc\":\"" + price + "\",\"open_pric\":\"100\","
                + "\"high_pric\":\"100\",\"low_pric\":\"100\"}]}";
    }

    private static LocalDate end() { return LocalDate.of(2026, 10, 23); }
    private static MinuteChartRequest request() { return new MinuteChartRequest("005930", end()); }
    private static MinuteCandle candle(String price) { return new MinuteCandle("20261023", "153000", price, "100", "100", "100"); }
    private static List<MinuteCandle> candles() { return List.of(candle("100")); }
}
