package com.antmillion.kis;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.dto.CurrentPriceRequest;
import com.antmillion.kis.dto.ForeignerOrganization;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisFrgnOrgnRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisInvestorFlowProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.marketdata.dto.InvestorFlow;
import com.antmillion.marketdata.provider.InvestorFlowProvider;
import com.antmillion.marketdata.service.InvestorSignalService;
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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 서비스·캐시·limiter에 HTTP·메모리 Redis 대역과 합성 자격증명만 연결합니다. */
public class KisInvestorFlowProviderTest {
    private static final String KEY = "kis:frgnorgn-ntby:v2:16:https://kis.test:6:000001";
    private static final String FLOW = "{\"output2\":[{\"frgn_fake_ntby_qty\":\"+0012\","
            + "\"orgn_fake_ntby_qty\":\"-003\",\"sum_fake_ntby_qty\":\"0009\"},"
            + "{\"frgn_fake_ntby_qty\":\"999\"}]}";
    private MemoryRedis redis;
    private MockRestServiceServer server;
    private KisApiService legacy;
    private InvestorFlowProvider provider;
    private RateLimitedKisApiClient limiter;
    private String baseUrl = "https://kis.test";
    private int baseUrlReads;

    @Before
    public void setUp() {
        redis = new MemoryRedis();
        for (String endpoint : List.of("https://kis.test", "https://real.kis.test", "https://virtual.kis.test")) {
            redis.values.put(new KisAccessTokenScope(endpoint, "synthetic-key", "synthetic-secret").getTokenKey(),
                    "synthetic-token");
        }
        RestTemplate rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        KisConfig config = new KisConfig() {
            @Override public String getBaseUrl() { baseUrlReads++; return baseUrl; }
        };
        config.appKey = "synthetic-key";
        config.appSecret = "synthetic-secret";
        config.accountNo = "synthetic-account";
        config.acntPrdtCd = "01";
        limiter = new RateLimitedKisApiClient(redis, config, new KisRestRateLimitConfig(18, 1));
        baseUrlReads = 0;
        legacy = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis), null, null,
                null, new KisFrgnOrgnRedisRepository(redis, new ObjectMapper()), null, limiter);
        provider = new KisInvestorFlowProvider(legacy);
    }

    @Test
    public void requestHeadersFirstRowRawStringsAndLegacyCacheTypeRemain() {
        expectFlow("000001", FLOW);
        assertEquals(new InvestorFlow("+0012", "-003", "0009"), provider.getInvestorFlow("000001"));
        assertTrue(redis.values.get(KEY) instanceof ForeignerOrganization);
        assertEquals(Long.valueOf(600), redis.ttls.get(KEY));
        assertEquals(1, redis.ttls.size());
        server.verify();
    }

    @Test
    public void snapshotDoesNotExposeMutableCache() {
        expectFlow("000001", FLOW);
        InvestorFlow snapshot = provider.getInvestorFlow("000001");
        ((ForeignerOrganization) redis.values.get(KEY)).setForeignNetQty("changed");
        assertEquals("+0012", snapshot.getForeignNetQuantity());
        assertEquals("changed", provider.getInvestorFlow("000001").getForeignNetQuantity());
        server.verify();
    }

    @Test
    public void cachedJsonPreservesEmptyMissingValuesWithoutBudgetOrTtlRefresh() {
        useFixedBudget(0);
        redis.values.put(KEY, Map.of("frgn_fake_ntby_qty", ""));
        assertEquals(new InvestorFlow("", null, null), provider.getInvestorFlow("000001"));
        assertTrue(redis.ttls.isEmpty());
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        server.verify();
    }

    @Test
    public void cacheIsSeparatedByStockCode() {
        expectFlow("000001", FLOW);
        expectFlow("000002", "{\"output2\":[{\"orgn_fake_ntby_qty\":\"2\"}]}");
        provider.getInvestorFlow("000001");
        assertEquals(new InvestorFlow(null, "2", null), provider.getInvestorFlow("000002"));
        assertEquals("+0012", provider.getInvestorFlow("000001").getForeignNetQuantity());
        assertEquals(2, redis.ttls.size());
        server.verify();
    }

    @Test
    public void invalidCachedShapeTriggersRemoteReload() {
        redis.values.put(KEY, "invalid-cache");
        expectFlow("000001", FLOW);
        assertEquals("0009", provider.getInvestorFlow("000001").getCombinedNetQuantity());
        server.verify();
    }

    @Test
    public void emptyRowsReturnMissingValuesAndAreNotCached() {
        expectFlow("000001", "{\"output2\":[]}");
        expectFlow("000001", "{\"output2\":[]}");
        assertEquals(new InvestorFlow(null, null, null), provider.getInvestorFlow("000001"));
        assertEquals(new InvestorFlow(null, null, null), provider.getInvestorFlow("000001"));
        assertFalse(redis.values.containsKey(KEY));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void missingFieldsInFirstRowRemainMissing() {
        expectFlow("000001", "{\"output2\":[{}]}");
        assertEquals(new InvestorFlow(null, null, null), provider.getInvestorFlow("000001"));
        assertTrue(redis.values.containsKey(KEY));
        server.verify();
    }

    @Test
    public void missingOutputAndBodyPropagateWithoutCache() {
        expectFlow("000001", "{}");
        expectFlow("000002", "");
        assertThrows(RuntimeException.class, () -> provider.getInvestorFlow("000001"));
        assertThrows(RuntimeException.class, () -> provider.getInvestorFlow("000002"));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void remoteFailurePropagatesWithoutCache() {
        server.expect(request -> assertEquals(url("000001"), request.getURI().toString()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(HttpServerErrorException.class, () -> provider.getInvestorFlow("000001"));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void cacheFirstAndUncachedLegacyCallsShareTheExistingBudget() {
        useFixedBudget(1);
        expectFlow("000001", FLOW);
        provider.getInvestorFlow("000001");
        assertEquals("+0012", provider.getInvestorFlow("000001").getForeignNetQuantity());
        assertThrows(RateLimitExceededException.class, () -> legacy.getCurrentPrice(
                CurrentPriceRequest.builder().marketCode("J").stockCode("000002").build()));
        assertThrows(RateLimitExceededException.class, () -> provider.getInvestorFlow("000002"));
        server.verify();
    }

    @Test
    public void explicitContextInjectsAdapterAndCommonService() {
        redis.values.put(KEY, Map.of("frgn_fake_ntby_qty", "1", "orgn_fake_ntby_qty", "2"));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(KisApiService.class, () -> legacy);
            context.register(KisInvestorFlowProvider.class, InvestorSignalService.class);
            context.refresh();
            assertSame(context.getBean(KisInvestorFlowProvider.class), context.getBean(InvestorFlowProvider.class));
            assertEquals("GREEN", context.getBean(InvestorSignalService.class).getSignalColor("000001"));
        }
        server.verify();
    }

    @Test
    public void realThenVirtualDoesNotReuseAnotherEnvironment() {
        assertEnvironmentIsolation("https://real.kis.test", "https://virtual.kis.test");
    }

    @Test
    public void virtualThenRealDoesNotReuseAnotherEnvironment() {
        assertEnvironmentIsolation("https://virtual.kis.test", "https://real.kis.test");
    }

    private void assertEnvironmentIsolation(String first, String second) {
        useFixedBudget(2);
        expectFlow(first, "000001", FLOW);
        expectFlow(second, "000001", "{\"output2\":[{\"frgn_fake_ntby_qty\":\"-12\"}]}");
        baseUrl = first;
        assertEquals("+0012", provider.getInvestorFlow("000001").getForeignNetQuantity());
        baseUrl = second;
        assertEquals("-12", provider.getInvestorFlow("000001").getForeignNetQuantity());
        baseUrl = first;
        assertEquals("+0012", provider.getInvestorFlow("000001").getForeignNetQuantity());
        baseUrl = second;
        assertEquals("-12", provider.getInvestorFlow("000001").getForeignNetQuantity());
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        assertEquals(2, redis.ttls.size());
        server.verify();
    }

    @Test
    public void legacyAndOtherProviderKeysAreNotReadOrChanged() {
        String legacyKey = "kis:frgnorgn-ntby000001";
        String otherProviderKey = KEY.replace("kis:", "kiwoom:");
        Map<String, String> stale = Map.of("frgn_fake_ntby_qty", "999");
        redis.values.put(legacyKey, stale);
        redis.values.put(otherProviderKey, stale);
        useFixedBudget(1);
        expectFlow("000001", FLOW);
        assertEquals("+0012", provider.getInvestorFlow("000001").getForeignNetQuantity());
        assertEquals("+0012", provider.getInvestorFlow("000001").getForeignNetQuantity());
        assertFalse(redis.reads.contains(legacyKey));
        assertFalse(redis.reads.contains(otherProviderKey));
        assertSame(stale, redis.values.get(legacyKey));
        assertSame(stale, redis.values.get(otherProviderKey));
        assertEquals(1, redis.ttls.size());
        assertEquals(Long.valueOf(600), redis.ttls.get(KEY));
        server.verify();
    }

    @Test
    public void depletedBudgetDoesNotFallBackToLegacyOrAnotherEnvironment() {
        redis.values.put("kis:frgnorgn-ntby000001", Map.of("frgn_fake_ntby_qty", "999"));
        useFixedBudget(1);
        expectFlow("000001", FLOW);
        provider.getInvestorFlow("000001");
        baseUrl = "https://virtual.kis.test";
        assertThrows(RateLimitExceededException.class, () -> provider.getInvestorFlow("000001"));
        baseUrl = "https://kis.test";
        assertEquals("+0012", provider.getInvestorFlow("000001").getForeignNetQuantity());
        assertEquals(1, redis.ttls.size());
        assertFalse(redis.reads.contains("kis:frgnorgn-ntby000001"));
        server.verify();
    }

    @Test
    public void cacheAndHttpUseTheSameSingleEnvironmentSnapshot() {
        // HTTP 대역 응답 중 환경이 바뀌어도 저장은 요청을 시작한 환경에 남습니다.
        server.expect(request -> assertEquals(url("000001"), request.getURI().toString()))
                .andRespond(request -> {
                    baseUrl = "https://virtual.kis.test";
                    return withSuccess(FLOW, MediaType.APPLICATION_JSON).createResponse(request);
                });
        assertEquals("+0012", provider.getInvestorFlow("000001").getForeignNetQuantity());
        assertEquals(1, baseUrlReads);
        assertTrue(redis.values.containsKey(KEY));
        baseUrl = "https://kis.test";
        useFixedBudget(0);
        assertEquals("+0012", provider.getInvestorFlow("000001").getForeignNetQuantity());
        assertEquals(2, baseUrlReads);
        assertEquals(1, redis.ttls.size());
        server.verify();
    }

    @Test
    public void delimitersNullAndEmptyConditionsDoNotCollide() {
        KisFrgnOrgnRedisRepository repository = new KisFrgnOrgnRedisRepository(redis, new ObjectMapper());
        String[][] conditions = {{"env:branch", "stock"}, {"env", "branch:stock"},
                {null, ""}, {"", null}, {"", ""}, {null, null}, {"null", ""}};
        for (int i = 0; i < conditions.length; i++) {
            ForeignerOrganization data = new ForeignerOrganization();
            data.setForeignNetQty(Integer.toString(i));
            repository.save(conditions[i][0], conditions[i][1], data);
        }
        assertEquals(conditions.length, redis.ttls.size());
        for (int i = 0; i < conditions.length; i++) {
            assertEquals(Integer.toString(i), repository.getKisFrgnorgnNtby(conditions[i][0], conditions[i][1])
                    .orElseThrow().getForeignNetQty());
        }
    }

    private void expectFlow(String stockCode, String json) {
        expectFlow("https://kis.test", stockCode, json);
    }

    private void expectFlow(String environment, String stockCode, String json) {
        server.expect(request -> {
            assertEquals(environment + "/uapi/domestic-stock/v1/quotations/investor-trend-estimate?MKSC_SHRN_ISCD="
                    + stockCode, request.getURI().toString());
            assertEquals(HttpMethod.GET, request.getMethod());
            assertEquals("Bearer synthetic-token", request.getHeaders().getFirst("Authorization"));
            assertEquals("synthetic-key", request.getHeaders().getFirst("appkey"));
            assertEquals("synthetic-secret", request.getHeaders().getFirst("appsecret"));
            assertEquals("HHPTJ04160200", request.getHeaders().getFirst("tr_id"));
            assertEquals("P", request.getHeaders().getFirst("custtype"));
        }).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String url(String stockCode) {
        return "https://kis.test/uapi/domestic-stock/v1/quotations/investor-trend-estimate?MKSC_SHRN_ISCD=" + stockCode;
    }

    private void useFixedBudget(int remaining) {
        Bucket budget = Bucket.builder().addLimit(Bandwidth.builder().capacity(Math.max(1, remaining))
                .refillIntervally(Math.max(1, remaining), Duration.ofDays(1)).build()).build();
        if (remaining == 0) budget.tryConsume(1);
        KisRateLimitTestSupport.fixedCacheBudget(limiter, budget);
    }

    private static class MemoryRedis extends RedisTemplate<String, Object> {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        final Map<String, Long> ttls = new ConcurrentHashMap<>();
        final List<String> reads = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, Object> opsForValue() {
            return (ValueOperations<String, Object>) Proxy.newProxyInstance(ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class}, (proxy, method, args) -> {
                        if ("get".equals(method.getName())) {
                            reads.add((String) args[0]);
                            return values.get(args[0]);
                        }
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
