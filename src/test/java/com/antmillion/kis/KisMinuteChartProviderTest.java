package com.antmillion.kis;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.dto.CurrentPriceRequest;
import com.antmillion.kis.dto.StreamMinutePrice;
import com.antmillion.kis.dto.StreamMinutePriceRequest;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisChartRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisMinuteChartProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.marketdata.dto.MinuteCandle;
import com.antmillion.marketdata.dto.MinuteChartRequest;
import com.antmillion.marketdata.dto.RecentMinuteCandle;
import com.antmillion.marketdata.provider.MinuteChartProvider;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 외부 연결 없이 HTTP·메모리 Redis 대역으로 기존 분봉 동작을 검증합니다. */
public class KisMinuteChartProviderTest {
    private static final String DAILY = "{\"output2\":["
            + "{\"stck_bsop_date\":\"20261021\",\"stck_cntg_hour\":\"153000\",\"stck_prpr\":\"071000\","
            + "\"stck_oprc\":\"70000\",\"stck_hgpr\":\"72000\",\"stck_lwpr\":\"69000\"},"
            + "{\"stck_bsop_date\":\"20261021\",\"stck_cntg_hour\":\"152900\",\"stck_prpr\":\"\","
            + "\"stck_oprc\":null,\"stck_hgpr\":\"0\"},null]}";
    private static final String RECENT = "{\"output2\":["
            + "{\"stck_cntg_hour\":\"153000\",\"stck_prpr\":\"71000\",\"stck_oprc\":\"70000\","
            + "\"stck_hgpr\":\"72000\",\"stck_lwpr\":\"69000\"},"
            + "{\"stck_cntg_hour\":\"152900\",\"stck_prpr\":null},null]}";
    private MemoryRedis redis;
    private MockRestServiceServer server;
    private KisApiService legacy;
    private MinuteChartProvider provider;
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
        legacy = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis),
                new KisChartRedisRepository(redis, new ObjectMapper()), null, null, null, null, limiter);
        provider = new KisMinuteChartProvider(legacy);
    }

    @Test
    public void dailyKeepsRequestHeadersReversedRowsStringsAndCacheType() {
        expectDaily(DAILY);
        List<MinuteCandle> rows = provider.getDailyMinutes(request());
        assertEquals(Arrays.asList(null, new MinuteCandle("20261021", "152900", "", null, "0", null),
                new MinuteCandle("20261021", "153000", "071000", "70000", "72000", "69000")), rows);
        List<?> cached = (List<?>) redis.values.get(cacheKey());
        assertNull(cached.get(0));
        assertTrue(cached.get(2) instanceof StreamMinutePrice);
        assertEquals(Long.valueOf(60), redis.ttls.get(cacheKey()));
        // 반환 공통 DTO는 원래 캐시 DTO와 분리된 불변 복사본입니다.
        ((StreamMinutePrice) cached.get(2)).setStckPrpr("changed");
        assertEquals("071000", rows.get(2).getClosePrice());
        server.verify();
    }

    @Test
    public void cachedJsonKeepsOrderAndMissingValuesWithoutHttpOrBudgetUse() {
        useFixedBudget(0);
        redis.values.put(cacheKey(), List.of(Map.of("stck_cntg_hour", "153000", "stck_prpr", "00100"),
                Map.of("stck_cntg_hour", "152900", "stck_prpr", "")));
        assertEquals(List.of(new MinuteCandle(null, "153000", "00100", null, null, null),
                new MinuteCandle(null, "152900", "", null, null, null)), provider.getDailyMinutes(request()));
        assertEquals(0, limiter.getAvailableTokens());
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void dailyCacheRetainsExistingServerDateKeyInsteadOfRequestDate() {
        useFixedBudget(1);
        expectDaily(DAILY);
        provider.getDailyMinutes(request());
        // 날짜·조회 조건 차원이 불완전한 기존 키 정책은 이 경계 분리에서 바꾸지 않습니다.
        assertEquals(3, provider.getDailyMinutes(new MinuteChartRequest("005930",
                request().getDate().minusDays(1))).size());
        assertEquals(1, redis.ttls.size());
        server.verify();
    }

    @Test
    public void dailyUnreadableCacheReloadsAndEmptyRemoteListIsCached() {
        useFixedBudget(1);
        redis.values.put(cacheKey(), "invalid-cache");
        expectDaily("{\"output2\":[]}");
        assertTrue(provider.getDailyMinutes(request()).isEmpty());
        assertTrue(provider.getDailyMinutes(request()).isEmpty());
        assertEquals(List.of(), redis.values.get(cacheKey()));
        assertEquals(Long.valueOf(60), redis.ttls.get(cacheKey()));
        server.verify();
    }

    @Test
    public void dailyMissingOutputReturnsEmptyWithoutCacheAndMissingBodyFails() {
        expectDaily("{}");
        expectDaily("{\"output2\":null}");
        server.expect(req -> assertDailyUrl(req.getURI().toString(), "J", "153000", "N", "", "20261021"))
                .andRespond(withStatus(HttpStatus.OK));
        assertTrue(provider.getDailyMinutes(request()).isEmpty());
        assertTrue(provider.getDailyMinutes(request()).isEmpty());
        assertThrows(RuntimeException.class, () -> provider.getDailyMinutes(request()));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void dailySharesBudgetWithOtherKisCallsAndCachedRowsStillWork() {
        useFixedBudget(1);
        expectDaily(DAILY);
        provider.getDailyMinutes(request());
        assertThrows(RateLimitExceededException.class, () -> legacy.getCurrentPrice(
                CurrentPriceRequest.builder().stockCode("000660").marketCode("J").build()));
        assertThrows(RateLimitExceededException.class, () -> provider.getDailyMinutes(
                new MinuteChartRequest("000660", request().getDate())));
        assertEquals(3, provider.getDailyMinutes(request()).size());
        server.verify();
    }

    @Test
    public void recentKeepsCurrentServerTimeNumericDefaultsOrderAndExistingLimiterBypass() {
        useFixedBudget(0);
        LocalTime before = LocalTime.now().withNano(0);
        expectRecent(RECENT, before);
        expectRecent(RECENT, before);
        List<RecentMinuteCandle> expected = Arrays.asList(
                new RecentMinuteCandle("153000", 71000, 70000, 72000, 69000),
                new RecentMinuteCandle("152900", 0, 0, 0, 0), null);
        assertEquals(expected, provider.getRecentMinutes("005930"));
        assertEquals(expected, provider.getRecentMinutes("005930"));
        assertEquals(0, limiter.getAvailableTokens());
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void recentKeepsNullOrEmptyOutputAndMissingBodyFailure() {
        LocalTime before = LocalTime.now().withNano(0);
        expectRecent("{}", before);
        expectRecent("{\"output2\":null}", before);
        expectRecent("{\"output2\":[]}", before);
        server.expect(req -> assertRecentUrl(req.getURI().toString(), before))
                .andRespond(withStatus(HttpStatus.OK));
        assertNull(provider.getRecentMinutes("005930"));
        assertNull(provider.getRecentMinutes("005930"));
        assertEquals(List.of(), provider.getRecentMinutes("005930"));
        assertThrows(RuntimeException.class, () -> provider.getRecentMinutes("005930"));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void bothRemoteFailuresPropagateWithoutRetryOrCacheWrite() {
        server.expect(req -> assertDailyUrl(req.getURI().toString(), "J", "153000", "N", "", "20261021"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        LocalTime before = LocalTime.now().withNano(0);
        server.expect(req -> assertRecentUrl(req.getURI().toString(), before))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(HttpServerErrorException.class, () -> provider.getDailyMinutes(request()));
        assertThrows(HttpServerErrorException.class, () -> provider.getRecentMinutes("005930"));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void legacyRequestAndAdapterConstructorInjectionRemainAvailable() {
        server.expect(req -> assertDailyUrl(req.getURI().toString(), "NX", "130000", "Y", "Y", "20260101"))
                .andRespond(withSuccess("{\"output2\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(legacy.getStreamMinutePrices(StreamMinutePriceRequest.builder()
                .condMrktDivCode("NX").inputIscd("005930").inputHour1("130000")
                .inputDate1("20260101").pwDataIncuYn("Y").fakeTickIncuYn("Y").build()).isEmpty());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(KisApiService.class, () -> legacy);
            context.register(KisMinuteChartProvider.class);
            context.refresh();
            assertSame(context.getBean(KisMinuteChartProvider.class), context.getBean(MinuteChartProvider.class));
        }
        server.verify();
    }

    private MinuteChartRequest request() {
        return new MinuteChartRequest("005930", LocalDate.of(2026, 10, 21));
    }

    private String cacheKey() {
        return "stream:minute:" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ":005930";
    }

    private void expectDaily(String json) {
        server.expect(req -> {
            assertDailyUrl(req.getURI().toString(), "J", "153000", "N", "", "20261021");
            assertHeaders(req.getMethod(), req.getHeaders(), "FHKST03010230");
            assertEquals("N", req.getHeaders().getFirst("tr_cont"));
        }).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void expectRecent(String json, LocalTime before) {
        server.expect(req -> {
            assertRecentUrl(req.getURI().toString(), before);
            assertHeaders(req.getMethod(), req.getHeaders(), "FHKST03010200");
        }).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void assertHeaders(HttpMethod method, org.springframework.http.HttpHeaders headers, String trId) {
        assertEquals(HttpMethod.GET, method);
        assertEquals("Bearer synthetic-token", headers.getFirst("Authorization"));
        assertEquals("synthetic-key", headers.getFirst("appkey"));
        assertEquals("synthetic-secret", headers.getFirst("appsecret"));
        assertEquals(trId, headers.getFirst("tr_id"));
        assertEquals("P", headers.getFirst("custtype"));
    }

    private void assertDailyUrl(String url, String market, String time, String past, String fake, String date) {
        assertEquals("https://kis.test/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice"
                + "?FID_COND_MRKT_DIV_CODE=" + market + "&FID_INPUT_ISCD=005930"
                + "&FID_INPUT_HOUR_1=" + time + "&FID_INPUT_DATE_1=" + date
                + "&FID_PW_DATA_INCU_YN=" + past + "&FID_FAKE_TICK_INCU_YN=" + fake, url);
    }

    private void assertRecentUrl(String url, LocalTime before) {
        Map<String, String> params = UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();
        assertTrue(url.startsWith("https://kis.test/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice?"));
        assertEquals(5, params.size());
        assertEquals("J", params.get("FID_COND_MRKT_DIV_CODE"));
        assertEquals("005930", params.get("FID_INPUT_ISCD"));
        assertEquals("Y", params.get("FID_PW_DATA_INCU_YN"));
        assertEquals("0", params.get("FID_ETC_CLS_CODE"));
        LocalTime actual = LocalTime.parse(params.get("FID_INPUT_HOUR_1"), DateTimeFormatter.ofPattern("HHmmss"));
        LocalTime after = LocalTime.now().withNano(0);
        assertTrue(before.isAfter(after) ? !actual.isBefore(before) || !actual.isAfter(after)
                : !actual.isBefore(before) && !actual.isAfter(after));
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
