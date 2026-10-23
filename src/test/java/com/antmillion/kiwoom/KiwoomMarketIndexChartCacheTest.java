package com.antmillion.kiwoom;

import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomMarketIndexChartRedisRepository;
import com.antmillion.kiwoom.service.KiwoomMarketIndexChartProvider;
import com.antmillion.kiwoom.service.KiwoomRestRateLimitTest;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.kis.controller.KisController;
import com.antmillion.marketdata.dto.MarketIndexCandle;
import com.antmillion.marketdata.dto.MarketIndexChartRequest;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 개발 설정·DB·실제 Redis/증권사 없이 고정 시각과 메모리/HTTP 대역으로 검증합니다. */
public class KiwoomMarketIndexChartCacheTest {
    private static final String REAL = "https://api.kiwoom.com";
    private static final String VIRTUAL = "https://mockapi.kiwoom.com";
    private KiwoomTokenServiceTest.MutableClock clock;
    private KiwoomTokenServiceTest.MemoryRedis redis;
    private KiwoomTokenServiceTest.MemoryRedis tokenRedis;
    private KiwoomMarketIndexChartRedisRepository cache;
    private KiwoomAccessTokenRedisRepository tokenCache;
    private KiwoomAccessTokenScope scope;
    private KiwoomTokenService tokens;
    private KiwoomRestRateLimiter limiter;
    private KiwoomMarketIndexChartProvider provider;
    private RestTemplate rest;
    private MockRestServiceServer server;

    @Before
    public void setUp() {
        clock = new KiwoomTokenServiceTest.MutableClock();
        redis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        tokenRedis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        cache = new KiwoomMarketIndexChartRedisRepository(redis, clock);
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
        provider = new KiwoomMarketIndexChartProvider(tokens, rest, limiter, cache);
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
    public void environmentIndexAndDateAreSeparatedInBothOrders() {
        List<MarketIndexChartRequest> requests = List.of(request(), new MarketIndexChartRequest("1001", end()),
                new MarketIndexChartRequest("0001", end().minusDays(1)));
        for (List<String> environments : List.of(List.of(REAL, VIRTUAL), List.of(VIRTUAL, REAL))) {
            setUp();
            int number = 0;
            Map<String, List<MarketIndexCandle>> expected = new HashMap<>();
            for (String environment : environments) {
                for (MarketIndexChartRequest query : requests) {
                    List<MarketIndexCandle> result = List.of(new MarketIndexCandle(query.getEndDate().format(
                            DateTimeFormatter.BASIC_ISO_DATE), (++number) + ".00", "1.00", "2.00", "0.50"));
                    cache.save(environment, query, result);
                    expected.put(environment + query, result);
                }
            }
            assertEquals(6, redis.values.size());
            for (String environment : environments) {
                for (MarketIndexChartRequest query : requests) {
                    assertEquals(expected.get(environment + query), cache.find(environment, query).orElseThrow());
                }
            }
        }
    }

    @Test
    public void providerMarketPeriodAndLegacyKeysAreNeverReusedOrChanged() {
        cache.save(VIRTUAL, request(), candles());
        String key = onlyKey();
        assertTrue(key.startsWith("kiwoom:chart:market-index:v1:"));
        assertTrue(key.contains(":1:U:4:0001:1:D:"));
        assertFalse(key.contains("synthetic"));
        Object saved = redis.values.remove(key);
        Map<String, Object> unrelated = new HashMap<>();
        unrelated.put(key.replace("kiwoom:", "kis:"), saved);
        unrelated.put(key.replace(":1:U:", ":1:J:"), saved);
        unrelated.put(key.replace(":1:D:", ":1:W:"), saved);
        unrelated.put("kis:chart:market-index:v2:legacy", saved);
        unrelated.put("kiwoom:chart:period:v1:legacy", saved);
        unrelated.put("kiwoom:chart:daily-minute:v1:legacy", saved);
        unrelated.put("kiwoom:price:single:v1:legacy", "0");
        redis.values.putAll(unrelated);
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        assertEquals(List.of(key), redis.reads);
        assertEquals(unrelated, redis.values);
    }

    @Test
    public void decimalsNullRowsDuplicatesEmptyValuesAndOrderSurviveSerialization() {
        List<MarketIndexCandle> result = Arrays.asList(candle("2500.50"), null, candle("2500.50"),
                new MarketIndexCandle("20261022", "999999999999999999999999.99", "", null, "0.00"));
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
        List<MarketIndexCandle> source = new ArrayList<>(candles());
        cache.save(VIRTUAL, request(), source);
        source.clear();
        cache.find(VIRTUAL, request()).orElseThrow().clear();
        assertEquals(candles(), cache.find(VIRTUAL, request()).orElseThrow());
    }

    @Test
    public void malformedEnvelopeTypesAndOverflowExpiryAreMisses() {
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
    public void invalidDatesOrderMissingFieldsAndNoncanonicalPointsAreMisses() throws Exception {
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
        for (String date : List.of("20260230", "20261024", "00000101", "2026-10-23", "")) {
            ObjectNode row = (ObjectNode) mapper.readTree(valid);
            row.put("date", date);
            redis.values.put(key, envelope("[" + row + "]"));
            assertFalse(cache.find(VIRTUAL, request()).isPresent());
        }
        for (String field : List.of("close", "open", "high", "low")) {
            for (String point : List.of("-1.00", "+1.00", "01.00", "1.5", "1", "1.000", "bad-sensitive")) {
                ObjectNode row = (ObjectNode) mapper.readTree(valid);
                row.put(field, point);
                redis.values.put(key, envelope("[" + row + "]"));
                assertFalse(cache.find(VIRTUAL, request()).isPresent());
            }
        }
        ObjectNode row = (ObjectNode) mapper.readTree(valid);
        row.put("close", 100);
        redis.values.put(key, envelope("[" + row + "]"));
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        redis.values.put(key, envelope("[" + valid.replace("20261023", "20261022") + ",null," + valid + "]"));
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
        assertEquals(candles(), provider.getMarketIndexChart(request()));
        assertTrue(tokenRedis.reads.isEmpty());
        tokenCache.save(scope, "synthetic-token", clock.instant().plusSeconds(200_000), 600);
        expectChart("101", "20261023", body("101", row("20261023", "100")));
        assertEquals(List.of(candle("1.00")), provider.getMarketIndexChart(new MarketIndexChartRequest("1001", end())));
        assertEquals(candles(), provider.getMarketIndexChart(request()));
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getMarketIndexChart(
                new MarketIndexChartRequest("0001", end().minusDays(1))));
        assertEquals(1, tokenRedis.reads.size());
        server.verify();
    }

    @Test
    public void successfulHttpIsCachedAndReusableWhileBudgetIsExhausted() {
        expectChart("001", "20261023", body("001", row("20261023", "-0250050")));
        assertEquals(candles(), provider.getMarketIndexChart(request()));
        tokenRedis.failReads = true;
        assertEquals(candles(), provider.getMarketIndexChart(request()));
        assertEquals(1, tokenRedis.reads.size());
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void successfulEmptyHttpResultIsCached() {
        expectChart("001", "20261023", body("001"));
        assertTrue(provider.getMarketIndexChart(request()).isEmpty());
        assertTrue(provider.getMarketIndexChart(request()).isEmpty());
        assertEquals(1, tokenRedis.reads.size());
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void coldAndWarmReadsKeepMoreThan100RowsAndNormalizedValues() {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 101; i++) rows.add(row(end().minusDays(i).format(DateTimeFormatter.BASIC_ISO_DATE), "-0001"));
        rows.add(1, "null");
        rows.add(2, rows.get(0));
        rows.add("{\"dt\":\"20260101\",\"cur_prc\":\"\",\"open_pric\":null,\"high_pric\":\"99999999999999999999\"}");
        expectChart("001", "20261023", body("001", rows.toArray(new String[0])));
        List<MarketIndexCandle> cold = provider.getMarketIndexChart(request());
        assertEquals(104, cold.size());
        assertEquals("0.01", cold.get(0).getCloseValue());
        assertNull(cold.get(1));
        assertEquals(cold.get(0), cold.get(2));
        assertEquals(new MarketIndexCandle("20260101", "", null, "999999999999999999.99", null), cold.get(103));
        assertEquals(cold, provider.getMarketIndexChart(request()));
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void expiryRequeriesHttpAndRefreshesOnlyAfterSuccess() {
        cache.save(VIRTUAL, request(), List.of(candle("0.99")));
        redis.expirations.clear();
        clock.advanceSeconds(60);
        expectChart("001", "20261023", body("001", row("20261023", "250050")));
        assertEquals(candles(), provider.getMarketIndexChart(request()));
        assertEquals(candles(), provider.getMarketIndexChart(request()));
        assertEquals(2, redis.writes.get());
        server.verify();
    }

    @Test
    public void corruptCacheIsReplacedByNormalHttpResult() {
        cache.save(VIRTUAL, request(), candles());
        redis.values.put(onlyKey(), "sensitive-invalid-json");
        expectChart("001", "20261023", body("001", row("20261023", "101")));
        assertEquals(List.of(candle("1.01")), provider.getMarketIndexChart(request()));
        assertEquals(List.of(candle("1.01")), provider.getMarketIndexChart(request()));
        server.verify();
    }

    @Test
    public void cacheReadOrWriteFailurePreservesNormalHttpResponse() {
        for (boolean failRead : List.of(true, false)) {
            setUp();
            redis.failReads = failRead;
            redis.failWrites = !failRead;
            expectChart("001", "20261023", body("001", row("20261023", "250050")));
            assertEquals(candles(), provider.getMarketIndexChart(request()));
            server.verify();
        }
    }

    @Test
    public void differentCredentialsSharePublicChartCacheWithoutSharingToken() {
        cache.save(VIRTUAL, request(), candles());
        KiwoomAccessTokenScope rotated = new KiwoomAccessTokenScope("virtual", "other-key", "other-secret");
        assertEquals(candles(), new KiwoomMarketIndexChartProvider(tokens(rotated), rest, limiter, cache)
                .getMarketIndexChart(request()));
        assertTrue(tokenRedis.reads.isEmpty());
        assertEquals(1, redis.values.size());
    }

    @Test
    public void cacheMissAtExhaustedBudgetNeverUsesTokenOrHttp() {
        limiter.acquireMarketIndexChart(tokens);
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getMarketIndexChart(request()));
        assertTrue(tokenRedis.reads.isEmpty());
        assertTrue(redis.values.isEmpty());
        server.verify();
    }

    @Test
    public void invalidRequestsAreRejectedBeforeAnyCacheOrTokenLookup() {
        for (MarketIndexChartRequest invalid : Arrays.asList(null,
                new MarketIndexChartRequest("001", end()), new MarketIndexChartRequest("0001", null),
                new MarketIndexChartRequest("0001", LocalDate.of(0, 1, 1)))) {
            assertThrows(IllegalArgumentException.class, () -> provider.getMarketIndexChart(invalid));
        }
        assertTrue(redis.reads.isEmpty());
        assertTrue(tokenRedis.reads.isEmpty());
    }

    @Test
    public void httpBusinessAndNormalizationFailuresAreNotCached() {
        for (String failure : List.of("{\"return_code\":1,\"return_msg\":\"sensitive\"}", "{}",
                body("001", row("20261023", "1.5")), body("101", row("20261023", "100")),
                body("001", row("20260230", "100")), body("001", row("20261023", "100"), row("20261024", "100")))) {
            setUp();
            expectChart("001", "20261023", failure);
            assertThrows(IllegalStateException.class, () -> provider.getMarketIndexChart(request()));
            assertTrue(redis.values.isEmpty());
            assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getMarketIndexChart(request()));
            server.verify();
        }
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR)) {
            setUp();
            server.expect(requestTo(VIRTUAL + "/api/dostk/chart")).andRespond(withStatus(status));
            assertThrows(IllegalStateException.class, () -> provider.getMarketIndexChart(request()));
            assertTrue(redis.values.isEmpty());
            server.verify();
        }
    }

    @Test
    public void tokenFailureIsNotCachedAndDoesNotRefundBudget() {
        tokenRedis.failReads = true;
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.getMarketIndexChart(request()));
        assertNull(error.getCause());
        assertTrue(redis.values.isEmpty());
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getMarketIndexChart(request()));
        server.verify();
    }

    @Test
    public void expiredEntryIsNotReturnedOnRemoteFailure() {
        cache.save(VIRTUAL, request(), candles());
        redis.expirations.clear();
        clock.advanceSeconds(60);
        server.expect(requestTo(VIRTUAL + "/api/dostk/chart")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThrows(IllegalStateException.class, () -> provider.getMarketIndexChart(request()));
        assertEquals(1, redis.writes.get());
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        server.verify();
    }

    @Test
    public void bothIndicesKeepExistingMvcJsonOnColdAndWarmReads() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        String day = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        for (String code : List.of("0001", "1001")) {
            setUp();
            expectChart(code.equals("0001") ? "001" : "101", day,
                    body(code.equals("0001") ? "001" : "101", row(day, "250050"), "null"));
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new KisController(null, null, null, null, provider, null)).build();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode cold = mapper.readTree(mvc.perform(get("/api/kis/marketIndex/" + code))
                    .andReturn().getResponse().getContentAsByteArray());
            assertTrue(cold.isArray());
            assertEquals(2, cold.size());
            assertEquals("2500.50", cold.get(0).get("bstp_nmix_prpr").asText());
            assertEquals(cold, mapper.readTree(mvc.perform(get("/api/kis/marketIndex/" + code))
                    .andReturn().getResponse().getContentAsByteArray()));
            assertEquals(1, tokenRedis.reads.size());
            server.verify();
        }
    }

    private KiwoomTokenService tokens(KiwoomAccessTokenScope tokenScope) {
        return new KiwoomTokenService(tokenScope, 600, rest, tokenCache,
                new KiwoomTokenServiceTest.LockClient().client(), clock);
    }

    private void expectChart(String code, String day, String response) {
        server.expect(requestTo(VIRTUAL + "/api/dostk/chart")).andExpect(method(HttpMethod.POST))
                .andExpect(header("api-id", "ka20006")).andExpect(header("Authorization", "Bearer synthetic-token"))
                .andExpect(request -> {
                    JsonNode body = new ObjectMapper().readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertEquals(code, body.path("inds_cd").asText());
                    assertEquals(day, body.path("base_dt").asText());
                    assertEquals(2, body.size());
                }).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private String envelope(String candlesJson) {
        return "{\"expires_at\":" + (clock.millis() + 60_000) + ",\"candles\":" + candlesJson + "}";
    }

    private String onlyKey() {
        assertEquals(1, redis.values.size());
        return redis.values.keySet().iterator().next();
    }

    private static String body(String code, String... rows) {
        return "{\"inds_cd\":\"" + code + "\",\"return_code\":0,\"inds_dt_pole_qry\":[" + String.join(",", rows) + "]}";
    }

    private static String row(String date, String value) {
        return "{\"dt\":\"" + date + "\",\"cur_prc\":\"" + value + "\",\"open_pric\":\"100\",\"high_pric\":\"200\",\"low_pric\":\"50\"}";
    }

    private static LocalDate end() { return LocalDate.of(2026, 10, 23); }
    private static MarketIndexChartRequest request() { return new MarketIndexChartRequest("0001", end()); }
    private static MarketIndexCandle candle(String value) { return new MarketIndexCandle("20261023", value, "1.00", "2.00", "0.50"); }
    private static List<MarketIndexCandle> candles() { return List.of(candle("2500.50")); }
}
