package com.antmillion.kis;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.dto.CurrentPriceRequest;
import com.antmillion.kis.dto.StockVolumeRank;
import com.antmillion.kis.dto.StockVolumeRankRequest;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.exception.KisMarketDataException;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisStockVolumeRankRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisStockRankingProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.marketdata.dto.RankedStock;
import com.antmillion.marketdata.provider.StockRankingProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 서비스·순위 캐시·공유 limiter를 HTTP·메모리 Redis 대역에만 연결합니다. */
public class KisStockRankingProviderTest {
    private static final String RANKS = "{\"output\":["
            + "{\"hts_kor_isnm\":\"합성종목\",\"mksc_shrn_iscd\":\"000001\",\"data_rank\":\"02\","
            + "\"stck_prpr\":\"071000\",\"acml_vol\":\"001234\",\"prdy_ctrt\":\"-1.20\"},"
            + "{\"mksc_shrn_iscd\":\"000002\",\"data_rank\":\"01\",\"stck_prpr\":\"\","
            + "\"acml_vol\":null,\"isFavorite\":true}]}";
    private MemoryRedis redis;
    private MockRestServiceServer server;
    private KisApiService legacy;
    private StockRankingProvider provider;
    private RateLimitedKisApiClient limiter;
    private KisStockVolumeRankRedisRepository ranks;
    private String baseUrl;

    @Before
    public void setUp() {
        redis = new MemoryRedis();
        for (String endpoint : List.of("https://kis.test", "https://virtual.kis.test")) {
            redis.values.put(new KisAccessTokenScope(endpoint, "synthetic-key", "synthetic-secret").getTokenKey(),
                    "synthetic-token");
        }
        RestTemplate rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        baseUrl = "https://kis.test";
        KisConfig config = new KisConfig() {
            @Override public String getBaseUrl() { return baseUrl; }
        };
        config.appKey = "synthetic-key";
        config.appSecret = "synthetic-secret";
        config.accountNo = "synthetic-account";
        config.acntPrdtCd = "01";
        limiter = new RateLimitedKisApiClient(redis, config, new KisRestRateLimitConfig(18, 1));
        ranks = new KisStockVolumeRankRedisRepository(redis, new ObjectMapper());
        legacy = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis), null, null,
                ranks, null, null, limiter, com.antmillion.marketdata.CircuitBreakerTestSupport.defaults());
        provider = new KisStockRankingProvider(legacy);
    }

    @Test
    public void tradingThenVolumeQueriesKeepIndependentResultsAndReuseTheirOwnCaches() {
        assertIndependentQueries(false);
    }

    @Test
    public void volumeThenTradingQueriesKeepIndependentResultsAndReuseTheirOwnCaches() {
        assertIndependentQueries(true);
    }

    private void assertIndependentQueries(boolean volumeFirst) {
        if (volumeFirst) {
            expectRanks("0", "0000001100", RANKS.replace("071000", "072000"));
            expectRanks("3", "0000000000", RANKS);
            assertEquals("072000", provider.getVolumeRanks().get(0).getCurrentPrice());
            assertEquals("071000", provider.getTradingValueRanks().get(0).getCurrentPrice());
        } else {
            expectRanks("3", "0000000000", RANKS);
            expectRanks("0", "0000001100", RANKS.replace("071000", "072000"));
            assertEquals("071000", provider.getTradingValueRanks().get(0).getCurrentPrice());
            assertEquals("072000", provider.getVolumeRanks().get(0).getCurrentPrice());
        }
        useFixedBudget(0);
        assertEquals("071000", provider.getTradingValueRanks().get(0).getCurrentPrice());
        assertEquals("072000", provider.getVolumeRanks().get(0).getCurrentPrice());
        assertEquals(2, redis.ttls.size());
        assertTrue(redis.ttls.values().stream().allMatch(ttl -> ttl == 60));
        server.verify();
    }

    @Test
    public void bothQueriesKeepDistinctConditionsHeadersRawValuesOrderAndCacheType() {
        expectRanks("3", "0000000000", RANKS);
        expectRanks("0", "0000001100", RANKS);
        List<RankedStock> expected = List.of(
                new RankedStock("합성종목", "000001", "02", "071000", "001234", "-1.20", false),
                new RankedStock(null, "000002", "01", "", null, null, true));
        assertEquals(expected, provider.getTradingValueRanks());
        assertTrue(((List<?>) redis.values.get(key("3", "0000000000"))).get(0) instanceof StockVolumeRank);
        assertEquals(Long.valueOf(60), redis.ttls.get(key("3", "0000000000")));
        assertEquals(expected, provider.getVolumeRanks());
        assertEquals(2, redis.ttls.size());
        server.verify();
    }

    @Test
    public void cachedJsonPreservesMissingFieldsAndNullableFavoriteWithoutBudgetUse() {
        useFixedBudget(0);
        redis.values.put(key("3", "0000000000"), List.of(
                Map.of("mksc_shrn_iscd", "000002", "isFavorite", true),
                Map.of("mksc_shrn_iscd", "000001", "stck_prpr", "")));
        assertEquals(List.of(new RankedStock(null, "000002", null, null, null, null, true),
                new RankedStock(null, "000001", null, "", null, null, false)), provider.getTradingValueRanks());
        com.fasterxml.jackson.databind.node.ArrayNode cached = new ObjectMapper().createArrayNode();
        cached.addObject().put("mksc_shrn_iscd", "000003").putNull("isFavorite");
        redis.values.put(key("0", "0000001100"), cached);
        assertNull(provider.getVolumeRanks().get(0).getFavorite());
        assertTrue(redis.ttls.isEmpty());
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        server.verify();
    }

    @Test
    public void snapshotDoesNotExposeMutableCachedDto() {
        expectRanks("3", "0000000000", RANKS);
        List<RankedStock> snapshot = provider.getTradingValueRanks();
        StockVolumeRank cached = (StockVolumeRank) ((List<?>) redis.values.get(key("3", "0000000000"))).get(0);
        cached.setPresentPrice("changed");
        cached.setIsFavorite(true);
        assertEquals("071000", snapshot.get(0).getCurrentPrice());
        assertEquals(Boolean.FALSE, snapshot.get(0).getFavorite());
        assertEquals("changed", provider.getTradingValueRanks().get(0).getCurrentPrice());
        assertEquals(1, redis.ttls.size());
        server.verify();
    }

    @Test
    public void nullRowsStayNullOnRemoteAndCachedPaths() {
        expectRanks("0", "0000001100", "{\"output\":[null]}");
        assertEquals(1, provider.getVolumeRanks().size());
        assertNull(provider.getVolumeRanks().get(0));
        server.verify();
    }

    @Test
    public void emptyListIsCachedAndReusedAfterBudgetExhaustion() {
        useFixedBudget(1);
        expectRanks("3", "0000000000", "{\"output\":[]}");
        assertTrue(provider.getTradingValueRanks().isEmpty());
        assertTrue(provider.getTradingValueRanks().isEmpty());
        assertEquals(List.of(), redis.values.get(key("3", "0000000000")));
        assertEquals(Long.valueOf(60), redis.ttls.get(key("3", "0000000000")));
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        server.verify();
    }

    @Test
    public void sharedBudgetRejectsUncachedLegacyAndProviderCalls() {
        useFixedBudget(1);
        expectRanks("3", "0000000000", RANKS);
        provider.getTradingValueRanks();
        assertThrows(RateLimitExceededException.class, () -> legacy.getCurrentPrice(
                CurrentPriceRequest.builder().stockCode("000001").marketCode("J").build()));
        assertThrows(RateLimitExceededException.class, () -> provider.getVolumeRanks());
        assertEquals(2, provider.getTradingValueRanks().size());
        assertFalse(redis.values.containsKey(key("0", "0000001100")));
        server.verify();
    }

    @Test
    public void missingOutputOrBodyStillFailsWithoutWritingCache() {
        expectRanks("3", "0000000000", "{}");
        server.expect(request -> assertEquals(url("0", "0000001100"), request.getURI().toString()))
                .andRespond(withStatus(HttpStatus.OK));
        assertThrows(RuntimeException.class, () -> provider.getTradingValueRanks());
        assertThrows(RuntimeException.class, () -> provider.getVolumeRanks());
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void remoteErrorPropagatesWithoutRetryOrCacheWrite() {
        server.expect(request -> assertEquals(url("3", "0000000000"), request.getURI().toString()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(KisMarketDataException.class, () -> provider.getTradingValueRanks());
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void unreadableCacheKeepsExistingRemoteReload() {
        redis.values.put(key("0", "0000001100"), "invalid-cache");
        expectRanks("0", "0000001100", RANKS);
        assertEquals(2, provider.getVolumeRanks().size());
        assertEquals(Long.valueOf(60), redis.ttls.get(key("0", "0000001100")));
        server.verify();
    }

    @Test
    public void legacyRequestAndAdapterConstructorInjectionRemainAvailable() {
        expectRanks("2", "legacy-exclusions", RANKS);
        assertEquals(2, legacy.getStockVolumeRanks(StockVolumeRankRequest.builder()
                .marketCode("J").screenCode("20171").inputCode("0000").divClassCode("0")
                .blngClassCode("2").targetClassCode("111111111").targetExlsClassCode("legacy-exclusions")
                .inputPrice1("0").inputPrice2("0").volumeCount("0").inputDate1("0").build()).size());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(KisApiService.class, () -> legacy);
            context.register(KisStockRankingProvider.class);
            context.refresh();
            assertSame(context.getBean(KisStockRankingProvider.class), context.getBean(StockRankingProvider.class));
        }
        server.verify();
    }

    @Test
    public void effectiveEndpointSeparatesEnvironmentsAndReturningReusesTheCache() {
        expectRanks("3", "0000000000", RANKS);
        baseUrl = "https://virtual.kis.test";
        expectRanks("3", "0000000000", RANKS.replace("071000", "072000"));
        baseUrl = "https://kis.test";
        assertEquals("071000", provider.getTradingValueRanks().get(0).getCurrentPrice());
        baseUrl = "https://virtual.kis.test";
        assertEquals("072000", provider.getTradingValueRanks().get(0).getCurrentPrice());
        useFixedBudget(0);
        baseUrl = "https://kis.test";
        assertEquals("071000", provider.getTradingValueRanks().get(0).getCurrentPrice());
        baseUrl = "https://virtual.kis.test";
        assertEquals("072000", provider.getTradingValueRanks().get(0).getCurrentPrice());
        assertEquals(2, redis.ttls.size());
        server.verify();
    }

    @Test
    public void everyQueryConditionHasAnIndependentCache() {
        List<Consumer<StockVolumeRankRequest>> changes = List.of(
                r -> r.setMarketCode("NX"), r -> r.setScreenCode("20172"),
                r -> r.setInputCode("0001"), r -> r.setDivClassCode("1"),
                r -> r.setBlngClassCode("0"), r -> r.setTargetClassCode("111111110"),
                r -> r.setTargetExlsClassCode("0000001100"), r -> r.setInputPrice1("100"),
                r -> r.setInputPrice2("1000"), r -> r.setVolumeCount("10"),
                r -> r.setInputDate1("20261021"));
        List<StockVolumeRankRequest> requests = new ArrayList<>();
        requests.add(request("3", "0000000000"));
        for (Consumer<StockVolumeRankRequest> change : changes) {
            StockVolumeRankRequest request = request("3", "0000000000");
            change.accept(request);
            requests.add(request);
        }
        for (int i = 0; i < requests.size(); i++) {
            StockVolumeRank row = new StockVolumeRank();
            row.setPresentPrice(String.valueOf(i));
            ranks.save(baseUrl, requests.get(i), List.of(row));
        }
        for (int i = 0; i < requests.size(); i++) {
            assertEquals(String.valueOf(i), ranks.getStockVolumeRanks(baseUrl, requests.get(i))
                    .get().get(0).getPresentPrice());
        }
        assertEquals(12, redis.ttls.size());
        assertTrue(redis.ttls.values().stream().allMatch(ttl -> ttl == 60));
        server.verify();
    }

    @Test
    public void incompleteLegacyAndOtherProviderKeysAreIgnoredAndLeftToExpire() {
        List<Map<String, String>> obsolete = List.of(Map.of("stck_prpr", "obsolete"));
        String oldKey = "stock:volume-rank";
        String otherProviderKey = key("3", "0000000000").replaceFirst("kis:", "kiwoom:");
        redis.values.put(oldKey, obsolete);
        redis.values.put(otherProviderKey, obsolete);
        expectRanks("3", "0000000000", RANKS);
        assertEquals("071000", provider.getTradingValueRanks().get(0).getCurrentPrice());
        assertEquals(obsolete, redis.values.get(oldKey));
        assertEquals(obsolete, redis.values.get(otherProviderKey));
        assertEquals(1, redis.ttls.size());
        server.verify();
    }

    @Test
    public void delimitersNullAndEmptyConditionsCannotAliasInRepositoryKeys() {
        StockVolumeRankRequest first = request("3", "0000000000");
        first.setMarketCode("A:B");
        first.setScreenCode("C");
        StockVolumeRankRequest second = request("3", "0000000000");
        second.setMarketCode("A");
        second.setScreenCode("B:C");
        List<StockVolumeRankRequest> requests = new ArrayList<>(List.of(first, second));
        for (String value : new String[]{null, "", "null"}) {
            StockVolumeRankRequest request = request("3", "0000000000");
            request.setMarketCode(value);
            requests.add(request);
        }
        for (int i = 0; i < requests.size(); i++) {
            StockVolumeRank row = new StockVolumeRank();
            row.setPresentPrice(String.valueOf(i));
            ranks.save(baseUrl, requests.get(i), List.of(row));
        }
        for (int i = 0; i < requests.size(); i++) {
            assertEquals(String.valueOf(i), ranks.getStockVolumeRanks(baseUrl, requests.get(i))
                    .get().get(0).getPresentPrice());
        }
        assertEquals(5, redis.ttls.size());
        server.verify();
    }

    private StockVolumeRankRequest request(String order, String excludedClasses) {
        return StockVolumeRankRequest.builder().marketCode("J").screenCode("20171").inputCode("0000")
                .divClassCode("0").blngClassCode(order).targetClassCode("111111111")
                .targetExlsClassCode(excludedClasses).inputPrice1("0").inputPrice2("0")
                .volumeCount("0").inputDate1("0").build();
    }

    private String key(String order, String excludedClasses) {
        StringBuilder key = new StringBuilder("kis:stock:volume-rank:v2");
        for (String value : new String[]{baseUrl, "J", "20171", "0000", "0", order, "111111111",
                excludedClasses, "0", "0", "0", "0"}) {
            key.append(':').append(value.length()).append(':').append(value);
        }
        return key.toString();
    }

    private void expectRanks(String order, String excludedClasses, String json) {
        String expectedUrl = url(order, excludedClasses);
        server.expect(request -> {
            assertEquals(expectedUrl, request.getURI().toString());
            assertEquals(HttpMethod.GET, request.getMethod());
            assertEquals("Bearer synthetic-token", request.getHeaders().getFirst("Authorization"));
            assertEquals("synthetic-key", request.getHeaders().getFirst("appkey"));
            assertEquals("synthetic-secret", request.getHeaders().getFirst("appsecret"));
            assertEquals("FHPST01710000", request.getHeaders().getFirst("tr_id"));
            assertEquals("P", request.getHeaders().getFirst("custtype"));
        }).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String url(String order, String excludedClasses) {
        return baseUrl + "/uapi/domestic-stock/v1/quotations/volume-rank"
                + "?FID_COND_MRKT_DIV_CODE=J&FID_COND_SCR_DIV_CODE=20171&FID_INPUT_ISCD=0000"
                + "&FID_DIV_CLS_CODE=0&FID_BLNG_CLS_CODE=" + order
                + "&FID_TRGT_CLS_CODE=111111111&FID_TRGT_EXLS_CLS_CODE=" + excludedClasses
                + "&FID_INPUT_PRICE_1=0&FID_INPUT_PRICE_2=0&FID_VOL_CNT=0&FID_INPUT_DATE_1=0";
    }

    private void useFixedBudget(int remaining) {
        Bucket budget = Bucket.builder().addLimit(Bandwidth.builder().capacity(1)
                .refillIntervally(1, Duration.ofDays(1)).build()).build();
        if (remaining == 0) budget.tryConsume(1);
        KisRateLimitTestSupport.fixedCacheBudget(limiter, budget);
    }

    private static class MemoryRedis extends RedisTemplate<String, Object> {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        final Map<String, Long> ttls = new ConcurrentHashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, Object> opsForValue() {
            return (ValueOperations<String, Object>) Proxy.newProxyInstance(ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class}, (proxy, method, args) -> {
                        if ("get".equals(method.getName())) return values.get(args[0]);
                        if ("set".equals(method.getName()) && args.length == 4) {
                            values.put((String) args[0], args[1]);
                            ttls.put((String) args[0], ((TimeUnit) args[3]).toSeconds((Long) args[2]));
                            return null;
                        }
                        throw new AssertionError("예상하지 않은 Redis 호출: " + method.getName());
                    });
        }
    }
}
