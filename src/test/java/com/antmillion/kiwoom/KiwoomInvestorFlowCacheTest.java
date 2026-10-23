package com.antmillion.kiwoom;

import com.antmillion.kis.controller.KisController;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.dto.KiwoomInvestorFlowRequest;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomInvestorFlowRedisRepository;
import com.antmillion.kiwoom.service.KiwoomInvestorFlowProvider;
import com.antmillion.kiwoom.service.KiwoomRestRateLimitTest;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.marketdata.dto.InvestorFlow;
import com.antmillion.marketdata.service.InvestorSignalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bucket4j.TimeMeter;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 개발 설정·DB·실제 Redis/증권사 없이 고정 시각과 메모리/HTTP 대역으로 검증합니다. */
public class KiwoomInvestorFlowCacheTest {
    private static final String REAL = "https://api.kiwoom.com";
    private static final String VIRTUAL = "https://mockapi.kiwoom.com";
    private KiwoomTokenServiceTest.MutableClock clock;
    private KiwoomTokenServiceTest.MemoryRedis redis;
    private KiwoomTokenServiceTest.MemoryRedis tokenRedis;
    private KiwoomTokenServiceTest.LockClient locks;
    private KiwoomInvestorFlowRedisRepository cache;
    private KiwoomAccessTokenRedisRepository tokenCache;
    private KiwoomAccessTokenScope scope;
    private KiwoomTokenService tokens;
    private KiwoomRestRateLimiter limiter;
    private KiwoomInvestorFlowProvider provider;
    private RestTemplate rest;
    private MockRestServiceServer server;

    @Before
    public void setUp() {
        clock = new KiwoomTokenServiceTest.MutableClock();
        redis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        tokenRedis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        locks = new KiwoomTokenServiceTest.LockClient();
        cache = new KiwoomInvestorFlowRedisRepository(redis, clock);
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
        provider = provider(tokens);
    }

    @Test
    public void sixHundredSecondTtlAndReadsDoNotExtendExpiration() {
        cache.save(VIRTUAL, request(), flow());
        String key = onlyKey();
        assertEquals(Long.valueOf(600_000), redis.ttls.get(key));
        long expiration = redis.expirations.get(key);
        clock.advanceSeconds(599);
        assertEquals(flow(), cache.find(VIRTUAL, request()).orElseThrow());
        assertEquals(Long.valueOf(expiration), redis.expirations.get(key));
        assertEquals(1, redis.writes.get());
        clock.advanceSeconds(1);
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
    }

    @Test
    public void absoluteExpiryAppliesEvenWithoutRedisTtl() {
        cache.save(VIRTUAL, request(), flow());
        redis.expirations.clear();
        clock.advanceSeconds(600);
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        assertEquals(1, redis.values.size());
    }

    @Test
    public void clockBeforeSaveTimeRejectsFutureCache() {
        cache.save(VIRTUAL, request(), flow());
        clock.advanceSeconds(-1);
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
    }

    @Test
    public void environmentsAndStockCodesAreSeparatedInBothOrders() {
        for (List<String> environments : List.of(List.of(REAL, VIRTUAL), List.of(VIRTUAL, REAL))) {
            for (List<String> codes : List.of(List.of("005930", "0000A1"), List.of("0000A1", "005930"))) {
                setUp();
                int number = 0;
                Map<String, InvestorFlow> expected = new HashMap<>();
                for (String environment : environments) {
                    for (String code : codes) {
                        InvestorFlow result = new InvestorFlow(Integer.toString(++number), null, null);
                        cache.save(environment, new KiwoomInvestorFlowRequest(code), result);
                        expected.put(environment + code, result);
                    }
                }
                assertEquals(4, redis.values.size());
                for (String environment : environments) {
                    for (String code : codes) {
                        assertEquals(expected.get(environment + code),
                                cache.find(environment, new KiwoomInvestorFlowRequest(code)).orElseThrow());
                    }
                }
            }
        }
    }

    @Test
    public void keyIncludesAllEffectiveWireConditions() {
        cache.save(VIRTUAL, request(), flow());
        JsonNode wire = new ObjectMapper().valueToTree(request());
        List<String> expected = new ArrayList<>(List.of(VIRTUAL, "ka10064"));
        for (String field : List.of("mrkt_tp", "amt_qty_tp", "trde_tp", "stk_cd")) {
            expected.add(wire.path(field).asText());
        }
        String encoded = onlyKey().substring("kiwoom:investor-flow:v1".length());
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
    public void lengthDelimitedNullAndEmptyConditionsCannotCollide() {
        for (String code : Arrays.asList(null, "", "a:b", "a", ":")) {
            InvestorFlow result = new InvestorFlow(Integer.toString(redis.values.size()), null, null);
            cache.save(VIRTUAL, new KiwoomInvestorFlowRequest(code), result);
            assertEquals(result, cache.find(VIRTUAL, new KiwoomInvestorFlowRequest(code)).orElseThrow());
        }
        assertEquals(5, redis.values.size());
    }

    @Test
    public void providerApiOtherCachesAndLegacyKeysAreNeverReusedOrChanged() {
        cache.save(VIRTUAL, request(), flow());
        String key = onlyKey();
        Object saved = redis.values.remove(key);
        Map<String, Object> unrelated = new HashMap<>();
        for (String other : List.of(key.replace("kiwoom:", "kis:"), key.replace("ka10064", "ka10065"),
                "kis:stock:frgnorgn:v2:legacy", "kis:frgnorgn:0000A1", "kiwoom:stock:ranking:v1:legacy",
                "kiwoom:auth:token:legacy", "kiwoom:auth:lock:legacy")) unrelated.put(other, saved);
        redis.values.putAll(unrelated);
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        assertEquals(List.of(key), redis.reads);
        assertEquals(unrelated, redis.values);
        assertFalse(key.contains("synthetic"));
    }

    @Test
    public void signedLargeNullAndBlankQuantitiesSurviveActualRedisSerialization() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.activateDefaultTyping(mapper.getPolymorphicTypeValidator(), ObjectMapper.DefaultTyping.NON_FINAL);
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);
        for (InvestorFlow result : List.of(flow(), new InvestorFlow("9223372036854775807", "1", "9223372036854775808"),
                new InvestorFlow("-9223372036854775808", "-1", "-9223372036854775809"),
                new InvestorFlow("0", "0", "0"), new InvestorFlow(null, "-2", null),
                new InvestorFlow("", "-2", null), new InvestorFlow(null, null, null))) {
            cache.save(VIRTUAL, request(), result);
            redis.values.replaceAll((key, value) -> serializer.deserialize(serializer.serialize(value)));
            assertEquals(result, cache.find(VIRTUAL, request()).orElseThrow());
        }
        cache.save(VIRTUAL, request(), null);
        assertEquals(7, redis.writes.get());
    }

    @Test
    public void malformedEnvelopesTypesAndExpiryOverflowAreMisses() {
        cache.save(VIRTUAL, request(), flow());
        String key = onlyKey();
        for (Object saved : List.of(100, "synthetic-sensitive-invalid-json", "null", "[]", "{}",
                "{\"expires_at\":\"1\"}", "{\"expires_at\":9223372036854775808}",
                "{\"expires_at\":-9223372036854775808}", "{\"expires_at\":1.5}")) {
            redis.values.put(key, saved);
            assertFalse(cache.find(VIRTUAL, request()).isPresent());
        }
    }

    @Test
    public void malformedMissingAndNonCanonicalQuantitiesAreMisses() throws Exception {
        cache.save(VIRTUAL, request(), flow());
        String key = onlyKey();
        String saved = (String) redis.values.get(key);
        for (String field : List.of("foreign", "organization", "combined")) {
            for (String invalid : List.of("+1", "01", "-0", "1.2", " 1", "1 ", "--1")) {
                ObjectNode value = (ObjectNode) new ObjectMapper().readTree(saved);
                value.put(field, invalid);
                redis.values.put(key, value.toString());
                assertFalse(cache.find(VIRTUAL, request()).isPresent());
            }
            ObjectNode value = (ObjectNode) new ObjectMapper().readTree(saved);
            value.put(field, 1);
            redis.values.put(key, value.toString());
            assertFalse(cache.find(VIRTUAL, request()).isPresent());
            value.remove(field);
            redis.values.put(key, value.toString());
            assertFalse(cache.find(VIRTUAL, request()).isPresent());
        }
    }

    @Test
    public void inconsistentSumOrSumWithMissingQuantityIsAMiss() {
        for (InvestorFlow result : List.of(new InvestorFlow("1", "2", "4"), new InvestorFlow("1", "2", null),
                new InvestorFlow(null, "2", "2"), new InvestorFlow("", "2", "2"))) {
            cache.save(VIRTUAL, request(), result);
            assertFalse(cache.find(VIRTUAL, request()).isPresent());
        }
    }

    @Test
    public void hitBypassesExhaustedBudgetTokenReadIssuanceAndHttp() {
        cache.save(VIRTUAL, request(), flow());
        limiter.acquireInvestorFlow(tokens);
        Map<String, Long> expirations = new HashMap<>(tokenRedis.expirations);
        tokenRedis.failReads = true;
        assertEquals(flow(), query());
        assertTrue(tokenRedis.reads.isEmpty());
        assertEquals(expirations, tokenRedis.expirations);
        assertTrue(locks.keys.isEmpty());
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireInvestorFlow(tokens));
        server.verify();
    }

    @Test
    public void hitDoesNotConsumeAvailableBudgetOrRefreshExpiredToken() {
        cache.save(VIRTUAL, request(), flow());
        tokenRedis.values.clear();
        assertEquals(flow(), query());
        limiter.acquireInvestorFlow(tokens);
        assertEquals(1, tokenRedis.writes.get());
        assertTrue(tokenRedis.reads.isEmpty());
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void publicFlowCacheIsSharedAcrossCredentialsInSameEnvironment() {
        cache.save(VIRTUAL, request(), flow());
        KiwoomTokenService other = tokens(new KiwoomAccessTokenScope("virtual", "other-key", "other-secret"));
        tokenRedis.failReads = true;
        assertEquals(flow(), provider(other).getInvestorFlow("0000A1"));
        assertEquals(1, redis.values.size());
        assertTrue(tokenRedis.reads.isEmpty());
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void invalidStockCodeFailsBeforeCacheBudgetTokenOrHttp() {
        for (String code : Arrays.asList(null, "", "12345", "005930_NX", "005930_AL", "00593a", " 005930")) {
            assertThrows(IllegalArgumentException.class, () -> provider.getInvestorFlow(code));
        }
        assertTrue(redis.reads.isEmpty());
        assertTrue(tokenRedis.reads.isEmpty());
        limiter.acquireInvestorFlow(tokens);
        server.verify();
    }

    @Test
    public void missUsesExistingLatestSelectionAndWarmResultAvoidsHttp() {
        expect().andRespond(withSuccess(body(row("153000", "-12", "3"), row("090000", "999", "999")), MediaType.APPLICATION_JSON));
        assertEquals(flow(), query());
        assertEquals(flow(), query());
        assertEquals(1, redis.writes.get());
        assertEquals(1, tokenRedis.reads.size());
        assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireInvestorFlow(tokens));
        server.verify();
    }

    @Test
    public void emptyResponseIsNotCachedAndNextQueryStillUsesHttp() {
        expect().andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        expect().andRespond(withSuccess(body(), MediaType.APPLICATION_JSON));
        assertEquals(new InvestorFlow(null, null, null), query());
        clock.advanceSeconds(1);
        assertEquals(new InvestorFlow(null, null, null), query());
        assertTrue(redis.values.isEmpty());
        assertEquals(0, redis.writes.get());
        assertEquals(2, tokenRedis.reads.size());
        server.verify();
    }

    @Test
    public void nonEmptyRowWithAllMissingValuesIsCachedWithoutInventingZero() {
        expect().andRespond(withSuccess(body(row("153000", null, null)), MediaType.APPLICATION_JSON));
        assertEquals(new InvestorFlow(null, null, null), query());
        assertEquals(new InvestorFlow(null, null, null), query());
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    @Test
    public void missWithExhaustedBudgetBlocksTokenAndHttp() {
        limiter.acquireInvestorFlow(tokens);
        assertThrows(KiwoomRateLimitExceededException.class, this::query);
        assertEquals(1, redis.reads.size());
        assertTrue(tokenRedis.reads.isEmpty());
        assertTrue(redis.values.isEmpty());
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void httpFailuresAreNotSavedRetriedOrRefunded() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR)) {
            setUp();
            expect().andRespond(withStatus(status).body("synthetic-sensitive"));
            assertSanitizedFailure();
            assertTrue(redis.values.isEmpty());
            assertThrows(KiwoomRateLimitExceededException.class, this::query);
            server.verify();
        }
    }

    @Test
    public void tokenFailureIsNotSavedAndStillConsumesBudget() {
        tokenRedis.failReads = true;
        assertSanitizedFailure();
        assertTrue(redis.values.isEmpty());
        assertThrows(KiwoomRateLimitExceededException.class, this::query);
        server.verify();
    }

    @Test
    public void malformedBusinessAndConflictingResponsesAreNotSaved() {
        for (String response : List.of("{}", "not-json", "{\"return_code\":1,\"return_msg\":\"synthetic-sensitive\"}",
                body(row("240000", "1", "2")), body(row("153000", "bad", "2")),
                body(row("153000", "1", "2"), row("153000", "2", "2")))) {
            setUp();
            expect().andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            assertSanitizedFailure();
            assertTrue(redis.values.isEmpty());
            assertThrows(KiwoomRateLimitExceededException.class, this::query);
            server.verify();
        }
    }

    @Test
    public void incompleteResponseIsNotCachedOrFollowed() {
        for (String continuation : List.of("Y", "unknown")) {
            setUp();
            HttpHeaders headers = new HttpHeaders();
            headers.set("cont-yn", continuation);
            headers.set("next-key", "synthetic-sensitive");
            expect().andRespond(withSuccess(body(row("153000", "1", "2")), MediaType.APPLICATION_JSON)
                    .headers(headers));
            assertSanitizedFailure();
            assertTrue(redis.values.isEmpty());
            server.verify();
        }
    }

    @Test
    public void expiredCacheDoesNotFallbackWhenRemoteFails() {
        cache.save(VIRTUAL, request(), flow());
        redis.expirations.clear();
        clock.advanceSeconds(600);
        expect().andRespond(withServerError());
        assertSanitizedFailure();
        assertEquals(1, redis.writes.get());
        assertFalse(cache.find(VIRTUAL, request()).isPresent());
        server.verify();
    }

    @Test
    public void corruptedCacheIsReplacedByValidatedRemoteResponse() {
        cache.save(VIRTUAL, request(), flow());
        redis.values.put(onlyKey(), "synthetic-sensitive-invalid-json");
        expect().andRespond(withSuccess(body(row("153000", "-12", "3")), MediaType.APPLICATION_JSON));
        assertEquals(flow(), query());
        assertEquals(flow(), query());
        assertEquals(2, redis.writes.get());
        server.verify();
    }

    @Test
    public void storageReadAndWriteFailuresKeepNormalResponseWithoutExposingErrors() {
        redis.failReads = true;
        redis.failWrites = true;
        expect().andRespond(withSuccess(body(row("153000", "-12", "3")), MediaType.APPLICATION_JSON));
        assertEquals(flow(), query());
        assertTrue(redis.values.isEmpty());
        assertThrows(KiwoomRateLimitExceededException.class, this::query);
        server.verify();
    }

    @Test
    public void coldAndWarmMvcJsonKeepSignalColorsAndNull() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new KisController(new InvestorSignalService(provider),
                null, null, null, null, null)).build();
        for (String[] values : new String[][]{{"12", "3", "GREEN"}, {"-12", "-3", "RED"},
                {"0", "3", "YELLOW"}, {"12", "-3", "YELLOW"}, {null, "3", null}}) {
            redis.values.clear();
            clock.advanceSeconds(1);
            server.reset();
            expect().andRespond(withSuccess(body(row("153000", values[0], values[1])), MediaType.APPLICATION_JSON));
            JsonNode cold = mvcJson(mvc);
            JsonNode warm = mvcJson(mvc);
            assertEquals(cold, warm);
            assertEquals(1, cold.size());
            if (values[2] == null) assertTrue(cold.get("signalColor").isNull());
            else assertEquals(values[2], cold.get("signalColor").asText());
            server.verify();
        }
    }

    @Test
    public void warmBlankAndOverflowKeepExistingSignalParsingFailure() {
        for (String value : List.of("", "9223372036854775808")) {
            redis.values.clear();
            clock.advanceSeconds(1);
            server.reset();
            expect().andRespond(withSuccess(body(row("153000", value, "1")), MediaType.APPLICATION_JSON));
            query();
            assertThrows(NumberFormatException.class, () -> new InvestorSignalService(provider).getSignalColor("0000A1"));
            server.verify();
        }
    }

    private JsonNode mvcJson(MockMvc mvc) throws Exception {
        org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(get("/api/kis/foreigner-organization/0000A1"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        return new ObjectMapper().readTree(response.getContentAsByteArray());
    }

    private void assertSanitizedFailure() {
        IllegalStateException error = assertThrows(IllegalStateException.class, this::query);
        assertNull(error.getCause());
        for (String value : List.of("synthetic-sensitive", "synthetic-token", "synthetic-key", "synthetic-secret")) {
            assertFalse(error.toString().contains(value));
        }
    }

    private KiwoomInvestorFlowProvider provider(KiwoomTokenService target) {
        return new KiwoomInvestorFlowProvider(target, rest, limiter, cache);
    }

    private KiwoomTokenService tokens(KiwoomAccessTokenScope target) {
        return new KiwoomTokenService(target, 600, rest, tokenCache, locks.client(), clock);
    }

    private InvestorFlow query() { return provider.getInvestorFlow("0000A1"); }
    private static InvestorFlow flow() { return new InvestorFlow("-12", "3", "-9"); }
    private static KiwoomInvestorFlowRequest request() { return new KiwoomInvestorFlowRequest("0000A1"); }
    private String onlyKey() { assertEquals(1, redis.values.size()); return redis.values.keySet().iterator().next(); }

    private ResponseActions expect() {
        return server.expect(requestTo(VIRTUAL + "/api/dostk/chart")).andExpect(method(HttpMethod.POST))
                .andExpect(header("api-id", "ka10064")).andExpect(header("Authorization", "Bearer synthetic-token"))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(http -> {
                    JsonNode json = new ObjectMapper().readTree(((MockClientHttpRequest) http).getBodyAsString());
                    assertEquals(new ObjectMapper().valueToTree(request()), json);
                    assertNull(http.getHeaders().getFirst("cont-yn"));
                    assertNull(http.getHeaders().getFirst("next-key"));
                });
    }

    private static String body(String... rows) {
        return "{\"return_code\":0,\"opmr_invsr_trde_chart\":[" + String.join(",", rows) + "]}";
    }

    private static String row(String time, String foreign, String organization) {
        return "{\"tm\":" + json(time) + ",\"frgnr_invsr\":" + json(foreign) + ",\"orgn\":" + json(organization) + "}";
    }

    private static String json(String value) { return value == null ? "null" : "\"" + value + "\""; }
}
