package com.antmillion.kis;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.dto.CurrentPrice;
import com.antmillion.kis.dto.CurrentPriceRequest;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisCurrentPriceProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.marketdata.dto.CurrentQuote;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
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
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 KIS 호출 코드를 HTTP·Redis 대역에만 연결하며 애플리케이션 설정은 로드하지 않습니다. */
public class KisCurrentPriceProviderTest {
    private MemoryRedis redis;
    private RestTemplate rest;
    private MockRestServiceServer server;
    private KisApiService legacy;
    private CurrentPriceProvider provider;
    private RateLimitedKisApiClient limiter;

    @Before
    public void setUp() {
        redis = new MemoryRedis();
        redis.values.put("kis:access_token", "synthetic-token");
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
        KisConfig config = new KisConfig() {
            @Override public String getBaseUrl() { return "https://kis.test"; }
        };
        config.appKey = "synthetic-key";
        config.appSecret = "synthetic-secret";
        limiter = new RateLimitedKisApiClient(redis);
        legacy = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis),
                null, null, null, null, null, limiter);
        provider = new KisCurrentPriceProvider(legacy);
    }

    @Test
    public void singlePriceKeepsKrxRequestTokenAndIntegerCache() {
        expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"71000\",\"prdy_ctrt\":\"1.25\"}}");
        assertEquals(Integer.valueOf(71000), provider.getCurrentPrice("005930"));
        assertEquals(71000, redis.values.get("current:price:005930"));
        assertEquals(Long.valueOf(10), redis.ttls.get("current:price:005930"));
        assertFalse(redis.values.containsKey("current:price:detail:005930"));
        server.verify();
    }

    @Test
    public void listKeepsOrderStringsAndPartialFailureWithoutLeakingCachedDtos() {
        expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"071000\",\"prdy_ctrt\":\"-1.20\"}}");
        server.expect(request -> assertEquals(url("000660", "J"), request.getURI().toString()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectQuote("035420", "J", "{\"output\":{\"stck_prpr\":\"0\",\"prdy_ctrt\":null}}");

        List<CurrentQuote> quotes = provider.getCurrentPrices(List.of("005930", "000660", "035420"));
        assertEquals(List.of(new CurrentQuote("005930", "071000", "-1.20"),
                new CurrentQuote("035420", "0", null)), quotes);
        CurrentPrice cached = (CurrentPrice) redis.values.get("current:price:detail:005930");
        assertNull(cached.getStockName());
        assertEquals(Long.valueOf(10), redis.ttls.get("current:price:detail:005930"));
        cached.setCurrentPrice("changed");
        assertEquals("071000", quotes.get(0).getCurrentPrice());
        assertFalse(redis.values.containsKey("current:price:005930"));
        server.verify();
    }

    @Test
    public void emptyListDoesNotCallHttpOrWriteCache() {
        assertTrue(provider.getCurrentPrices(List.of()).isEmpty());
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void exhaustedSharedBudgetKeepsIntegerCacheFallback() {
        useFixedBudget(1);
        expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"71000\"}}");
        assertEquals(Integer.valueOf(71000), provider.getCurrentPrice("005930"));
        // 같은 limiter를 사용하는 기존 호출도 추가 HTTP 요청 없이 같은 정수 캐시를 사용합니다.
        assertEquals(Integer.valueOf(71000), legacy.getCurrentPrice(request("005930", "J")));
        server.verify();
    }

    @Test
    public void exhaustedBudgetKeepsDetailedCacheTypeAndMissingValue() {
        useFixedBudget(0);
        CurrentPrice cached = new CurrentPrice();
        cached.setStockCode("005930");
        cached.setStockName("캐시에 남은 이름");
        cached.setPrdyCtrt("+0.00");
        redis.values.put("current:price:detail:005930", cached);
        List<CurrentQuote> quotes = provider.getCurrentPrices(List.of("005930"));
        assertEquals(List.of(new CurrentQuote("005930", null, "+0.00")), quotes);
        assertSame(cached, redis.values.get("current:price:detail:005930"));
        assertEquals("캐시에 남은 이름", cached.getStockName());
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void exhaustedBudgetWithoutCachePreservesSingleFailureAndListOmission() {
        useFixedBudget(0);
        assertThrows(RateLimitExceededException.class, () -> provider.getCurrentPrice("005930"));
        assertTrue(provider.getCurrentPrices(List.of("005930")).isEmpty());
        server.verify();
    }

    @Test
    public void remoteFailureDoesNotIntroduceNewStaleFallback() {
        redis.values.put("current:price:005930", 1000);
        server.expect(request -> assertEquals(url("005930", "J"), request.getURI().toString()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(HttpServerErrorException.class, () -> provider.getCurrentPrice("005930"));
        assertEquals(1000, redis.values.get("current:price:005930"));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void invalidSinglePayloadStillFailsWithoutWritingCache() {
        expectQuote("005930", "J", "{\"output\":{\"stck_prpr\":\"invalid\"}}");
        assertThrows(NumberFormatException.class, () -> provider.getCurrentPrice("005930"));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void legacyRequestContractAndAdapterBeanRemainAvailable() {
        expectQuote("005930", "NX", "{\"output\":{\"stck_prpr\":\"71000\"}}");
        assertEquals(Integer.valueOf(71000), legacy.getCurrentPrice(request("005930", "NX")));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // root context나 설정 scan 없이 실제 adapter의 생성자 주입만 검증합니다.
            context.registerBean(KisApiService.class, () -> legacy);
            context.register(KisCurrentPriceProvider.class);
            context.refresh();
            assertSame(context.getBean(KisCurrentPriceProvider.class), context.getBean(CurrentPriceProvider.class));
        }
        server.verify();
    }

    private void expectQuote(String code, String market, String json) {
        server.expect(request -> {
            assertEquals(url(code, market), request.getURI().toString());
            assertEquals(HttpMethod.GET, request.getMethod());
            assertEquals("Bearer synthetic-token", request.getHeaders().getFirst("Authorization"));
            assertEquals("synthetic-key", request.getHeaders().getFirst("appkey"));
            assertEquals("synthetic-secret", request.getHeaders().getFirst("appsecret"));
            assertEquals("FHKST01010100", request.getHeaders().getFirst("tr_id"));
            assertEquals("P", request.getHeaders().getFirst("custtype"));
        }).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String url(String code, String market) {
        return "https://kis.test/uapi/domestic-stock/v1/quotations/inquire-price"
                + "?FID_COND_MRKT_DIV_CODE=" + market + "&FID_INPUT_ISCD=" + code;
    }

    private CurrentPriceRequest request(String code, String market) {
        return CurrentPriceRequest.builder().stockCode(code).marketCode(market).build();
    }

    private void useFixedBudget(int remaining) {
        // 벽시계 대기 없이 제한·캐시 분기를 검증하도록 긴 주기의 테스트 버킷만 주입합니다.
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
