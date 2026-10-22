package com.antmillion.kis;

import com.antmillion.kis.config.KisConfig;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 서비스·캐시·limiter에 HTTP·메모리 Redis 대역과 합성 자격증명만 연결합니다. */
public class KisInvestorFlowProviderTest {
    private static final String KEY = "kis:frgnorgn-ntby000001";
    private static final String FLOW = "{\"output2\":[{\"frgn_fake_ntby_qty\":\"+0012\","
            + "\"orgn_fake_ntby_qty\":\"-003\",\"sum_fake_ntby_qty\":\"0009\"},"
            + "{\"frgn_fake_ntby_qty\":\"999\"}]}";
    private MemoryRedis redis;
    private MockRestServiceServer server;
    private KisApiService legacy;
    private InvestorFlowProvider provider;
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
        assertEquals(0, limiter.getAvailableTokens());
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

    private void expectFlow(String stockCode, String json) {
        server.expect(request -> {
            assertEquals(url(stockCode), request.getURI().toString());
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
