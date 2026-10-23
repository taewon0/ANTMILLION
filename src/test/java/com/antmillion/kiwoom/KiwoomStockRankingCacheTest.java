package com.antmillion.kiwoom;

import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.dto.KiwoomStockRankingRequest;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomStockRankingRedisRepository;
import com.antmillion.kiwoom.service.KiwoomRestRateLimitTest;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomStockRankingProvider;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.kis.controller.KisController;
import com.antmillion.marketdata.dto.RankedStock;
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
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 개발 설정·DB·실제 Redis/증권사 없이 고정 시각과 메모리/HTTP 대역으로 검증합니다. */
public class KiwoomStockRankingCacheTest {
    private static final String REAL = "https://api.kiwoom.com";
    private static final String VIRTUAL = "https://mockapi.kiwoom.com";
    private KiwoomTokenServiceTest.MutableClock clock;
    private KiwoomTokenServiceTest.MemoryRedis redis;
    private KiwoomTokenServiceTest.MemoryRedis tokenRedis;
    private KiwoomStockRankingRedisRepository cache;
    private KiwoomAccessTokenRedisRepository tokenCache;
    private KiwoomAccessTokenScope scope;
    private KiwoomTokenService tokens;
    private KiwoomRestRateLimiter limiter;
    private KiwoomStockRankingProvider provider;
    private RestTemplate rest;
    private MockRestServiceServer server;

    @Before
    public void setUp() {
        clock = new KiwoomTokenServiceTest.MutableClock();
        redis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        tokenRedis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        cache = new KiwoomStockRankingRedisRepository(redis, clock);
        tokenCache = new KiwoomAccessTokenRedisRepository(tokenRedis, clock);
        scope = new KiwoomAccessTokenScope("virtual", "synthetic-key", "synthetic-secret");
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        tokens = tokens(scope);
        tokenCache.save(scope, "synthetic-token", clock.instant().plusSeconds(200_000), 600);
        limiter = KiwoomRestRateLimitTest.withClock(new TimeMeter() {
            @Override public long currentTimeNanos() { return clock.millis() * 1_000_000; }
            @Override public boolean isWallClockBased() { return false; }
        });
        provider = new KiwoomStockRankingProvider(tokens, rest, limiter, cache);
    }

    @Test
    public void sixtySecondTtlAndReadsDoNotExtendExpiration() {
        cache.save(VIRTUAL, request(false), stocks());
        String key = onlyKey();
        assertEquals(Long.valueOf(60_000), redis.ttls.get(key));
        long expiration = redis.expirations.get(key);
        clock.advanceSeconds(59);
        assertEquals(stocks(), cache.find(VIRTUAL, request(false)).orElseThrow());
        assertEquals(Long.valueOf(expiration), redis.expirations.get(key));
        assertEquals(1, redis.writes.get());
        clock.advanceSeconds(1);
        assertFalse(cache.find(VIRTUAL, request(false)).isPresent());
    }

    @Test
    public void absoluteExpiryStillAppliesWithoutRedisTtl() {
        cache.save(VIRTUAL, request(false), stocks());
        redis.expirations.clear();
        clock.advanceSeconds(60);
        assertFalse(cache.find(VIRTUAL, request(false)).isPresent());
        assertEquals(1, redis.values.size());
    }

    @Test
    public void clockBeforeSaveTimeRejectsFutureCache() {
        cache.save(VIRTUAL, request(false), stocks());
        clock.advanceSeconds(-1);
        assertFalse(cache.find(VIRTUAL, request(false)).isPresent());
    }

    @Test
    public void environmentAndSortAreSeparatedInBothOrders() {
        for (List<String> environments : List.of(List.of(REAL, VIRTUAL), List.of(VIRTUAL, REAL))) {
            for (boolean volumeFirst : List.of(false, true)) {
                setUp();
                int number = 0;
                Map<String, List<RankedStock>> expected = new HashMap<>();
                for (String environment : environments) {
                    for (boolean volume : List.of(volumeFirst, !volumeFirst)) {
                        List<RankedStock> result = List.of(stock(Integer.toString(++number)));
                        cache.save(environment, request(volume), result);
                        expected.put(environment + volume, result);
                    }
                }
                assertEquals(4, redis.values.size());
                for (String environment : environments) {
                    for (boolean volume : List.of(false, true)) {
                        assertEquals(expected.get(environment + volume), cache.find(environment, request(volume)).orElseThrow());
                    }
                }
            }
        }
    }

    @Test
    public void keyIncludesAllEffectiveWireConditions() throws Exception {
        cache.save(VIRTUAL, request(false), stocks());
        JsonNode wire = new ObjectMapper().valueToTree(request(false));
        List<String> expected = new ArrayList<>(List.of(VIRTUAL, "ka10030"));
        for (String field : List.of("mrkt_tp", "sort_tp", "mang_stk_incls", "crd_tp", "trde_qty_tp",
                "pric_tp", "trde_prica_tp", "mrkt_open_tp", "stex_tp")) {
            expected.add(wire.path(field).asText());
        }
        // 실제 HTTP 본문의 고정 조건도 모두 키에 보존되어야 합니다.
        String encoded = onlyKey().substring("kiwoom:stock:ranking:v1".length());
        List<String> decoded = new ArrayList<>();
        while (!encoded.isEmpty()) {
            int end = encoded.indexOf(':', 1);
            int length = Integer.parseInt(encoded.substring(1, end));
            decoded.add(encoded.substring(end + 1, end + 1 + length));
            encoded = encoded.substring(end + 1 + length);
        }
        assertEquals(expected, decoded);
    }

    @Test
    public void nullEmptyAndDelimitedConditionsCannotCollide() throws Exception {
        for (String value : Arrays.asList(null, "", "a:b", "a", ":")) {
            KiwoomStockRankingRequest query = request(false);
            Field condition = KiwoomStockRankingRequest.class.getDeclaredField("sort");
            condition.setAccessible(true);
            condition.set(query, value);
            List<RankedStock> result = List.of(stock(Integer.toString(redis.values.size())));
            cache.save(VIRTUAL, query, result);
            assertEquals(result, cache.find(VIRTUAL, query).orElseThrow());
        }
        assertEquals(5, redis.values.size());
    }

    @Test
    public void providerApiAndLegacyKeysAreNeverReusedOrChanged() {
        cache.save(VIRTUAL, request(false), stocks());
        String key = onlyKey();
        assertTrue(key.startsWith("kiwoom:stock:ranking:v1:"));
        assertTrue(key.contains(":7:ka10030:"));
        assertFalse(key.contains("synthetic"));
        Object saved = redis.values.remove(key);
        Map<String, Object> unrelated = new HashMap<>();
        unrelated.put(key.replace("kiwoom:", "kis:"), saved);
        unrelated.put(key.replace("ka10030", "ka10031"), saved);
        unrelated.put("kis:stock:volume-rank:v2:legacy", saved);
        unrelated.put("stock:volume-rank", saved);
        unrelated.put("kiwoom:chart:market-index:v1:legacy", saved);
        unrelated.put("kiwoom:price:single:v1:legacy", "0");
        unrelated.put("kiwoom:auth:token:legacy", "synthetic-token");
        unrelated.put("kiwoom:auth:lock:legacy", "synthetic-lock");
        redis.values.putAll(unrelated);
        assertFalse(cache.find(VIRTUAL, request(false)).isPresent());
        assertEquals(List.of(key), redis.reads);
        assertEquals(unrelated, redis.values);
    }

    @Test
    public void normalizedValuesNullRowsDuplicatesAndOrderSurviveRedisSerialization() {
        List<RankedStock> result = Arrays.asList(stock("71000"), null,
                new RankedStock("합성종목", "005930", "3", "71000", "1234", "-1.20", false),
                new RankedStock(null, "0000A1", "4", "999999999999999999999999", "0", "+0.00", false),
                new RankedStock("", "", "5", "", null, "", false),
                new RankedStock(null, null, "6", null, "999999999999999999999999", null, false));
        cache.save(VIRTUAL, request(false), result);
        ObjectMapper mapper = new ObjectMapper();
        mapper.activateDefaultTyping(mapper.getPolymorphicTypeValidator(), ObjectMapper.DefaultTyping.NON_FINAL);
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);
        redis.values.replaceAll((key, value) -> serializer.deserialize(serializer.serialize(value)));
        assertEquals(result, cache.find(VIRTUAL, request(false)).orElseThrow());
    }

    @Test
    public void emptyResultIsAHitAndNullListIsNotSaved() {
        cache.save(VIRTUAL, request(false), null);
        assertTrue(redis.values.isEmpty());
        cache.save(VIRTUAL, request(false), List.of());
        assertTrue(cache.find(VIRTUAL, request(false)).orElseThrow().isEmpty());
    }

    @Test
    public void callerListMutationsCannotChangeStoredSnapshot() {
        List<RankedStock> source = new ArrayList<>(stocks());
        cache.save(VIRTUAL, request(false), source);
        source.clear();
        cache.find(VIRTUAL, request(false)).orElseThrow().clear();
        assertEquals(stocks(), cache.find(VIRTUAL, request(false)).orElseThrow());
    }

    @Test
    public void malformedEnvelopeTypesAndOverflowExpiryAreMisses() {
        cache.save(VIRTUAL, request(false), stocks());
        String key = onlyKey();
        for (Object value : List.of(100, "sensitive-invalid-json", "null", "[]", "{}",
                "{\"expires_at\":\"1\",\"stocks\":[]}",
                "{\"expires_at\":9223372036854775808,\"stocks\":[]}",
                "{\"expires_at\":-9223372036854775808,\"stocks\":[]}", envelope("null"), envelope("{}"))) {
            redis.values.put(key, value);
            assertFalse(cache.find(VIRTUAL, request(false)).isPresent());
        }
    }

    @Test
    public void missingFieldsWrongTypesRanksAndNoncanonicalValuesAreMisses() throws Exception {
        cache.save(VIRTUAL, request(false), stocks());
        String key = onlyKey();
        ObjectMapper mapper = new ObjectMapper();
        String valid = mapper.readTree((String) redis.values.get(key)).path("stocks").get(0).toString();
        for (String field : List.of("name", "code", "rank", "price", "volume", "rate", "favorite")) {
            ObjectNode row = (ObjectNode) mapper.readTree(valid);
            row.remove(field);
            assertInvalidRow(key, row.toString());
            row = (ObjectNode) mapper.readTree(valid);
            row.put(field, 100);
            assertInvalidRow(key, row.toString());
        }
        Map<String, List<String>> invalid = Map.of("code", List.of("005930_NX", "bad", "00593a"),
                "rank", List.of("0", "2", "01", ""), "price", List.of("-1", "+1", "01", "1.5", "bad"),
                "volume", List.of("-1", "+1", "01", "1.5", "bad"), "rate", List.of("NaN", "1e3", "1,000", " 1.2 "));
        for (Map.Entry<String, List<String>> entry : invalid.entrySet()) {
            for (String text : entry.getValue()) {
                ObjectNode row = (ObjectNode) mapper.readTree(valid);
                row.put(entry.getKey(), text);
                assertInvalidRow(key, row.toString());
            }
        }
        assertInvalidRow(key, valid.replace("\"favorite\":false", "\"favorite\":true"));
        assertInvalidRow(key, valid.replace("\"favorite\":false", "\"favorite\":null"));
        assertInvalidRow(key, "null," + valid); // null 행도 원래 순위 위치를 소비합니다.
    }

    @Test
    public void thirtyRowsArePreservedAndOversizedCacheIsAMiss() {
        List<RankedStock> result = new ArrayList<>();
        for (int i = 1; i <= 30; i++) result.add(new RankedStock(null, null, Integer.toString(i), null, null, null, false));
        cache.save(VIRTUAL, request(false), result);
        assertEquals(result, cache.find(VIRTUAL, request(false)).orElseThrow());
        result.add(new RankedStock(null, null, "31", null, null, null, false));
        cache.save(VIRTUAL, request(false), result);
        assertFalse(cache.find(VIRTUAL, request(false)).isPresent());
    }

    @Test
    public void redisFailureIsMissAndWriteFailureDoesNotEscape() {
        redis.failWrites = true;
        cache.save(VIRTUAL, request(false), stocks());
        assertTrue(redis.values.isEmpty());
        redis.failReads = true;
        assertFalse(cache.find(VIRTUAL, request(false)).isPresent());
    }

    @Test
    public void warmHitNeedsNoTokenAndLeavesFullRequestBudgetAvailable() {
        cache.save(VIRTUAL, request(false), stocks());
        tokenRedis.values.clear();
        assertEquals(stocks(), provider.getTradingValueRanks());
        assertTrue(tokenRedis.reads.isEmpty());
        tokenCache.save(scope, "synthetic-token", clock.instant().plusSeconds(200_000), 600);
        expect(true).andRespond(withSuccess(body(row("72000")), MediaType.APPLICATION_JSON));
        assertEquals(List.of(stock("72000")), provider.getVolumeRanks());
        assertEquals(stocks(), provider.getTradingValueRanks());
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireStockRanking(tokens));
        assertEquals(1, tokenRedis.reads.size());
        server.verify();
    }

    @Test
    public void successfulHttpIsReusableAtExhaustedBudgetWithoutExtendingTokenExpiry() {
        Map<String, Long> expirations = new HashMap<>(tokenRedis.expirations);
        expect(false).andRespond(withSuccess(body(row("-00071000")), MediaType.APPLICATION_JSON));
        assertEquals(stocks(), provider.getTradingValueRanks());
        tokenRedis.failReads = true;
        assertEquals(stocks(), provider.getTradingValueRanks());
        assertEquals(1, tokenRedis.reads.size());
        assertEquals(expirations, tokenRedis.expirations);
        assertEquals(1, tokenRedis.writes.get());
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void successfulEmptyHttpResultIsCachedForBothSorts() {
        for (boolean volume : List.of(false, true)) {
            setUp();
            expect(volume).andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
            assertTrue(query(volume).isEmpty());
            assertTrue(query(volume).isEmpty());
            assertEquals(1, tokenRedis.reads.size());
            assertEquals(1, redis.writes.get());
            server.verify();
        }
    }

    @Test
    public void coldAndWarmReadsKeepTopThirtyPositionsAndNormalizedValues() {
        List<String> rows = new ArrayList<>(Arrays.asList("null", "{}", row("-00071000"), row("-00071000"),
                "{\"stk_cd\":\"0000A1\",\"cur_prc\":\"\",\"trde_qty\":\"999999999999999999999999\",\"flu_rt\":\"+0.00\"}"));
        while (rows.size() < 31) rows.add(row("1"));
        expect(false).andRespond(withSuccess(body(rows.toArray(new String[0])), MediaType.APPLICATION_JSON));
        List<RankedStock> cold = provider.getTradingValueRanks();
        assertEquals(30, cold.size());
        assertNull(cold.get(0));
        assertEquals(new RankedStock(null, null, "2", null, null, null, false), cold.get(1));
        assertEquals("71000", cold.get(2).getCurrentPrice());
        assertEquals("005930", cold.get(2).getStockCode());
        assertEquals("4", cold.get(3).getRank());
        assertEquals(new RankedStock(null, "0000A1", "5", "", "999999999999999999999999", "+0.00", false), cold.get(4));
        assertEquals("30", cold.get(29).getRank());
        assertEquals(cold, provider.getTradingValueRanks());
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void expiryRequeriesHttpAndRefreshesOnlyAfterSuccess() {
        cache.save(VIRTUAL, request(false), List.of(stock("1")));
        redis.expirations.clear();
        clock.advanceSeconds(60);
        expect(false).andRespond(withSuccess(body(row("71000")), MediaType.APPLICATION_JSON));
        assertEquals(stocks(), provider.getTradingValueRanks());
        assertEquals(stocks(), provider.getTradingValueRanks());
        assertEquals(2, redis.writes.get());
        server.verify();
    }

    @Test
    public void corruptCacheIsReplacedByNormalHttpResult() {
        cache.save(VIRTUAL, request(false), stocks());
        redis.values.put(onlyKey(), "sensitive-invalid-json");
        expect(false).andRespond(withSuccess(body(row("72000")), MediaType.APPLICATION_JSON));
        assertEquals(List.of(stock("72000")), provider.getTradingValueRanks());
        assertEquals(List.of(stock("72000")), provider.getTradingValueRanks());
        server.verify();
    }

    @Test
    public void cacheReadOrWriteFailurePreservesNormalHttpResponse() {
        for (boolean failRead : List.of(true, false)) {
            setUp();
            redis.failReads = failRead;
            redis.failWrites = !failRead;
            expect(false).andRespond(withSuccess(body(row("71000")), MediaType.APPLICATION_JSON));
            assertEquals(stocks(), provider.getTradingValueRanks());
            server.verify();
        }
    }

    @Test
    public void differentCredentialsSharePublicRankingCacheWithoutSharingToken() {
        cache.save(VIRTUAL, request(false), stocks());
        KiwoomAccessTokenScope rotated = new KiwoomAccessTokenScope("virtual", "other-key", "other-secret");
        assertEquals(stocks(), new KiwoomStockRankingProvider(tokens(rotated), rest, limiter, cache).getTradingValueRanks());
        assertTrue(tokenRedis.reads.isEmpty());
        assertEquals(1, redis.values.size());
    }

    @Test
    public void httpQueriesKeepEnvironmentsAndSortsSeparateOnColdAndWarmReads() {
        for (boolean realFirst : List.of(false, true)) {
            for (boolean volumeFirst : List.of(false, true)) {
                setUp();
                KiwoomAccessTokenScope real = new KiwoomAccessTokenScope("real", "synthetic-key", "synthetic-secret");
                tokenCache.save(real, "synthetic-token", clock.instant().plusSeconds(200_000), 600);
                for (KiwoomAccessTokenScope target : List.of(realFirst ? real : scope, realFirst ? scope : real)) {
                    KiwoomStockRankingProvider selected = new KiwoomStockRankingProvider(tokens(target), rest, limiter, cache);
                    for (boolean volume : List.of(volumeFirst, !volumeFirst)) {
                        server.reset();
                        clock.advanceSeconds(1);
                        String price = (target == real ? "1" : "2") + (volume ? "3" : "4");
                        expect(target, volume).andRespond(withSuccess(body(row(price)), MediaType.APPLICATION_JSON));
                        assertEquals(List.of(stock(price)), volume ? selected.getVolumeRanks() : selected.getTradingValueRanks());
                        assertEquals(List.of(stock(price)), volume ? selected.getVolumeRanks() : selected.getTradingValueRanks());
                        server.verify();
                    }
                }
                assertEquals(4, redis.values.size());
                assertEquals(4, tokenRedis.reads.size());
            }
        }
    }

    @Test
    public void cacheMissAtExhaustedBudgetNeverUsesTokenOrHttp() {
        limiter.acquireStockRanking(tokens);
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getTradingValueRanks());
        assertTrue(tokenRedis.reads.isEmpty());
        assertTrue(redis.values.isEmpty());
        server.verify();
    }

    @Test
    public void httpBusinessAndNormalizationFailuresAreNotCachedOrRefunded() {
        for (String failure : List.of("{\"return_code\":1,\"return_msg\":\"sensitive\"}", "{}",
                "{\"tdy_trde_qty_upper\":null}", "", "null", body(row("1"), row("1.5")))) {
            setUp();
            expect(false).andRespond(withSuccess(failure, MediaType.APPLICATION_JSON));
            assertThrows(IllegalStateException.class, () -> provider.getTradingValueRanks());
            assertTrue(redis.values.isEmpty());
            assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getTradingValueRanks());
            server.verify();
        }
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR)) {
            setUp();
            expect(false).andRespond(withStatus(status));
            assertThrows(IllegalStateException.class, () -> provider.getTradingValueRanks());
            assertTrue(redis.values.isEmpty());
            server.verify();
        }
    }

    @Test
    public void tokenFailureIsNotCachedAndDoesNotRefundBudget() {
        tokenRedis.failReads = true;
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> provider.getTradingValueRanks());
        assertNull(error.getCause());
        assertTrue(redis.values.isEmpty());
        assertThrows(KiwoomRateLimitExceededException.class, () -> provider.getTradingValueRanks());
        server.verify();
    }

    @Test
    public void expiredEntryIsNotReturnedOnRemoteFailure() {
        cache.save(VIRTUAL, request(false), stocks());
        redis.expirations.clear();
        clock.advanceSeconds(60);
        expect(false).andRespond(withServerError());
        assertThrows(IllegalStateException.class, () -> provider.getTradingValueRanks());
        assertEquals(1, redis.writes.get());
        assertFalse(cache.find(VIRTUAL, request(false)).isPresent());
        server.verify();
    }

    @Test
    public void threeExistingMvcResponsesMatchOnColdAndWarmReads() throws Exception {
        for (String path : List.of("/api/kis/volumeRank", "/api/kis/volumeRank/paged?page=1&size=1", "/api/kis/stocksByCode")) {
            setUp();
            expect(path.endsWith("stocksByCode")).andRespond(withSuccess(body(row("71000")), MediaType.APPLICATION_JSON));
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new KisController(null, null, null, null, null, provider)).build();
            ObjectMapper mapper = new ObjectMapper();
            List<JsonNode> responses = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                responses.add(mapper.readTree(mvc.perform(path.endsWith("stocksByCode")
                        ? post(path).contentType(MediaType.APPLICATION_JSON).content("{\"stockCodes\":[\"005930\"]}")
                        : get(path)).andReturn().getResponse().getContentAsByteArray()));
            }
            assertEquals(responses.get(0), responses.get(1));
            JsonNode rows = responses.get(0).isArray() ? responses.get(0) : responses.get(0).path("data");
            assertEquals(1, rows.size());
            assertEquals(7, rows.get(0).size());
            assertEquals("71000", rows.get(0).path("stck_prpr").asText());
            assertTrue(rows.get(0).path("stck_prpr").isTextual());
            assertEquals(1, tokenRedis.reads.size());
            server.verify();
        }
    }

    private KiwoomTokenService tokens(KiwoomAccessTokenScope tokenScope) {
        return new KiwoomTokenService(tokenScope, 600, rest, tokenCache,
                new KiwoomTokenServiceTest.LockClient().client(), clock);
    }

    private ResponseActions expect(boolean volume) { return expect(scope, volume); }

    private ResponseActions expect(KiwoomAccessTokenScope target, boolean volume) {
        return server.expect(requestTo(target.getBaseUrl() + "/api/dostk/rkinfo")).andExpect(method(HttpMethod.POST))
                .andExpect(header("api-id", "ka10030")).andExpect(header("Authorization", "Bearer synthetic-token"))
                .andExpect(http -> {
                    JsonNode json = new ObjectMapper().readTree(((MockClientHttpRequest) http).getBodyAsString());
                    assertEquals(volume ? "1" : "3", json.path("sort_tp").asText());
                    assertEquals(volume ? "16" : "0", json.path("mang_stk_incls").asText());
                    assertEquals("000", json.path("mrkt_tp").asText());
                    assertEquals("1", json.path("stex_tp").asText());
                });
    }

    private void assertInvalidRow(String key, String row) {
        redis.values.put(key, envelope("[" + row + "]"));
        assertFalse(cache.find(VIRTUAL, request(false)).isPresent());
    }

    private String envelope(String stocksJson) {
        return "{\"expires_at\":" + (clock.millis() + 60_000) + ",\"stocks\":" + stocksJson + "}";
    }

    private String onlyKey() {
        assertEquals(1, redis.values.size());
        return redis.values.keySet().iterator().next();
    }

    private List<RankedStock> query(boolean volume) { return volume ? provider.getVolumeRanks() : provider.getTradingValueRanks(); }
    private static KiwoomStockRankingRequest request(boolean volume) { return new KiwoomStockRankingRequest(volume); }
    private static List<RankedStock> stocks() { return List.of(stock("71000")); }
    private static RankedStock stock(String price) { return new RankedStock("합성종목", "005930", "1", price, "1234", "-1.20", false); }
    private static String body(String... rows) { return "{\"return_code\":0,\"tdy_trde_qty_upper\":[" + String.join(",", rows) + "]}"; }
    private static String row(String price) { return "{\"stk_nm\":\"합성종목\",\"stk_cd\":\"005930\",\"cur_prc\":\"" + price + "\",\"trde_qty\":\"1234\",\"flu_rt\":\"-1.20\"}"; }
}
