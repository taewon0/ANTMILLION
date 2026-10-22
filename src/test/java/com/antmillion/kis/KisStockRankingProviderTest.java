package com.antmillion.kis;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.dto.CurrentPriceRequest;
import com.antmillion.kis.dto.StockVolumeRank;
import com.antmillion.kis.dto.StockVolumeRankRequest;
import com.antmillion.kis.exception.RateLimitExceededException;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    @Before
    public void setUp() {
        redis = new MemoryRedis();
        redis.values.put("kis:access_token", "synthetic-token");
        RestTemplate rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        KisConfig config = new KisConfig() {
            @Override public String getBaseUrl() { return "https://kis.test"; }
        };
        config.appKey = "synthetic-key";
        config.appSecret = "synthetic-secret";
        limiter = new RateLimitedKisApiClient(redis);
        legacy = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis), null, null,
                new KisStockVolumeRankRedisRepository(redis, new ObjectMapper()), null, null, limiter);
        provider = new KisStockRankingProvider(legacy);
    }

    @Test
    public void bothQueriesKeepDistinctConditionsHeadersRawValuesOrderAndCacheType() {
        expectRanks("3", "0000000000", RANKS);
        expectRanks("0", "0000001100", RANKS);
        List<RankedStock> expected = List.of(
                new RankedStock("합성종목", "000001", "02", "071000", "001234", "-1.20", false),
                new RankedStock(null, "000002", "01", "", null, null, true));
        assertEquals(expected, provider.getTradingValueRanks());
        assertTrue(((List<?>) redis.values.get("stock:volume-rank")).get(0) instanceof StockVolumeRank);
        assertEquals(Long.valueOf(60), redis.ttls.get("stock:volume-rank"));
        redis.values.remove("stock:volume-rank");
        assertEquals(expected, provider.getVolumeRanks());
        assertEquals(1, redis.ttls.size());
        server.verify();
    }

    @Test
    public void cachedJsonPreservesMissingFieldsAndNullableFavoriteWithoutBudgetUse() {
        useFixedBudget(0);
        redis.values.put("stock:volume-rank", List.of(
                Map.of("mksc_shrn_iscd", "000002", "isFavorite", true),
                Map.of("mksc_shrn_iscd", "000001", "stck_prpr", "")));
        assertEquals(List.of(new RankedStock(null, "000002", null, null, null, null, true),
                new RankedStock(null, "000001", null, "", null, null, false)), provider.getTradingValueRanks());
        com.fasterxml.jackson.databind.node.ArrayNode cached = new ObjectMapper().createArrayNode();
        cached.addObject().put("mksc_shrn_iscd", "000003").putNull("isFavorite");
        redis.values.put("stock:volume-rank", cached);
        assertNull(provider.getVolumeRanks().get(0).getFavorite());
        assertTrue(redis.ttls.isEmpty());
        assertEquals(0, limiter.getAvailableTokens());
        server.verify();
    }

    @Test
    public void snapshotDoesNotExposeCachedDtoAndExistingSharedKeyRemainsRequestInsensitive() {
        expectRanks("3", "0000000000", RANKS);
        List<RankedStock> snapshot = provider.getTradingValueRanks();
        StockVolumeRank cached = (StockVolumeRank) ((List<?>) redis.values.get("stock:volume-rank")).get(0);
        cached.setPresentPrice("changed");
        cached.setIsFavorite(true);
        assertEquals("071000", snapshot.get(0).getCurrentPrice());
        assertEquals(Boolean.FALSE, snapshot.get(0).getFavorite());
        // 두 조회가 같은 캐시를 사용하는 기존 한계는 이번 변경에서 수정하지 않습니다.
        assertEquals("changed", provider.getVolumeRanks().get(0).getCurrentPrice());
        assertEquals(1, redis.ttls.size());
        server.verify();
    }

    @Test
    public void nullRowsStayNullOnRemoteAndCachedPaths() {
        expectRanks("0", "0000001100", "{\"output\":[null]}");
        assertEquals(1, provider.getVolumeRanks().size());
        assertNull(provider.getTradingValueRanks().get(0));
        server.verify();
    }

    @Test
    public void emptyListIsCachedAndReusedAfterBudgetExhaustion() {
        useFixedBudget(1);
        expectRanks("3", "0000000000", "{\"output\":[]}");
        assertTrue(provider.getTradingValueRanks().isEmpty());
        assertTrue(provider.getVolumeRanks().isEmpty());
        assertEquals(List.of(), redis.values.get("stock:volume-rank"));
        assertEquals(Long.valueOf(60), redis.ttls.get("stock:volume-rank"));
        assertEquals(0, limiter.getAvailableTokens());
        server.verify();
    }

    @Test
    public void sharedBudgetRejectsUncachedLegacyAndProviderCalls() {
        useFixedBudget(1);
        expectRanks("3", "0000000000", RANKS);
        provider.getTradingValueRanks();
        assertThrows(RateLimitExceededException.class, () -> legacy.getCurrentPrice(
                CurrentPriceRequest.builder().stockCode("000001").marketCode("J").build()));
        redis.values.remove("stock:volume-rank");
        assertThrows(RateLimitExceededException.class, () -> provider.getVolumeRanks());
        assertFalse(redis.values.containsKey("stock:volume-rank"));
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
        assertThrows(HttpServerErrorException.class, () -> provider.getTradingValueRanks());
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void unreadableCacheKeepsExistingRemoteReload() {
        redis.values.put("stock:volume-rank", "invalid-cache");
        expectRanks("0", "0000001100", RANKS);
        assertEquals(2, provider.getVolumeRanks().size());
        assertEquals(Long.valueOf(60), redis.ttls.get("stock:volume-rank"));
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

    private void expectRanks(String order, String excludedClasses, String json) {
        server.expect(request -> {
            assertEquals(url(order, excludedClasses), request.getURI().toString());
            assertEquals(HttpMethod.GET, request.getMethod());
            assertEquals("Bearer synthetic-token", request.getHeaders().getFirst("Authorization"));
            assertEquals("synthetic-key", request.getHeaders().getFirst("appkey"));
            assertEquals("synthetic-secret", request.getHeaders().getFirst("appsecret"));
            assertEquals("FHPST01710000", request.getHeaders().getFirst("tr_id"));
            assertEquals("P", request.getHeaders().getFirst("custtype"));
        }).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String url(String order, String excludedClasses) {
        return "https://kis.test/uapi/domestic-stock/v1/quotations/volume-rank"
                + "?FID_COND_MRKT_DIV_CODE=J&FID_COND_SCR_DIV_CODE=20171&FID_INPUT_ISCD=0000"
                + "&FID_DIV_CLS_CODE=0&FID_BLNG_CLS_CODE=" + order
                + "&FID_TRGT_CLS_CODE=111111111&FID_TRGT_EXLS_CLS_CODE=" + excludedClasses
                + "&FID_INPUT_PRICE_1=0&FID_INPUT_PRICE_2=0&FID_VOL_CNT=0&FID_INPUT_DATE_1=0";
    }

    private void useFixedBudget(int remaining) {
        Bucket budget = Bucket.builder().addLimit(Bandwidth.builder().capacity(1)
                .refillIntervally(1, Duration.ofDays(1)).build()).build();
        if (remaining == 0) budget.tryConsume(1);
        ReflectionTestUtils.setField(limiter, "bucket", budget);
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
