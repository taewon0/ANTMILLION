package com.antmillion.kis;

import com.antmillion.config.GlobalExceptionHandler;
import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.dto.CurrentPriceRequest;
import com.antmillion.kis.dto.StreamMinutePrice;
import com.antmillion.kis.dto.StreamMinutePriceRequest;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.exception.KisMarketDataException;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisChartRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisMinuteChartProvider;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.antmillion.marketdata.dto.MinuteCandle;
import com.antmillion.marketdata.dto.MinuteChartRequest;
import com.antmillion.marketdata.dto.RecentMinuteCandle;
import com.antmillion.marketdata.provider.MinuteChartProvider;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
    private KisChartRedisRepository charts;
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
        charts = new KisChartRedisRepository(redis, new ObjectMapper());
        legacy = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis),
                charts, null, null, null, null, limiter);
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
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void differentRequestDatesDoNotReuseTheFirstDate() {
        assertDatesRemainSeparated("20261021", "20261020");
    }

    @Test
    public void reversedRequestDatesDoNotReuseTheFirstDate() {
        assertDatesRemainSeparated("20261020", "20261021");
    }

    private void assertDatesRemainSeparated(String first, String second) {
        useFixedBudget(2);
        expectDailyDate(first, DAILY.replace("20261021", first));
        expectDailyDate(second, DAILY.replace("20261021", second).replace("071000", "072000"));
        MinuteChartRequest firstRequest = new MinuteChartRequest("005930", LocalDate.parse(first,
                DateTimeFormatter.BASIC_ISO_DATE));
        MinuteChartRequest secondRequest = new MinuteChartRequest("005930", LocalDate.parse(second,
                DateTimeFormatter.BASIC_ISO_DATE));
        assertEquals("071000", provider.getDailyMinutes(firstRequest).get(2).getClosePrice());
        assertEquals("072000", provider.getDailyMinutes(secondRequest).get(2).getClosePrice());
        assertEquals(second, provider.getDailyMinutes(secondRequest).get(2).getDate());
        assertEquals(first, provider.getDailyMinutes(firstRequest).get(2).getDate());
        assertEquals(2, redis.ttls.size());
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        server.verify();
    }

    private void expectDailyDate(String date, String json) {
        server.expect(req -> assertDailyUrl(req.getURI().toString(), "J", "153000", "N", "", date))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    public void allSixQueryConditionsHaveIndependentCachesAndReuseTheirOwnRows() {
        List<StreamMinutePriceRequest> requests = List.of(dailyRequest(),
                dailyRequest("NX", "005930", "153000", "20261021", "N", ""),
                dailyRequest("J", "000660", "153000", "20261021", "N", ""),
                dailyRequest("J", "005930", "130000", "20261021", "N", ""),
                dailyRequest("J", "005930", "153000", "20261020", "N", ""),
                dailyRequest("J", "005930", "153000", "20261021", "Y", ""),
                dailyRequest("J", "005930", "153000", "20261021", "N", "Y"));
        useFixedBudget(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            StreamMinutePriceRequest request = requests.get(i);
            String json = DAILY.replace("071000", String.valueOf(71000 + i));
            expectDailyRequest(request, json);
        }
        for (int i = 0; i < requests.size(); i++) {
            assertEquals(String.valueOf(71000 + i), legacy.getStreamMinutePrices(requests.get(i)).get(2).getStckPrpr());
        }
        for (int i = requests.size() - 1; i >= 0; i--) {
            assertEquals(String.valueOf(71000 + i), legacy.getStreamMinutePrices(requests.get(i)).get(2).getStckPrpr());
            assertEquals(Long.valueOf(60), redis.ttls.get(cacheKey(baseUrl, requests.get(i))));
        }
        assertEquals(requests.size(), redis.ttls.size());
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        server.verify();
    }

    @Test
    public void effectiveEndpointSeparatesEnvironmentsAndReusesEachCache() {
        useFixedBudget(2);
        expectDaily(DAILY);
        baseUrl = "https://virtual.kis.test";
        expectDaily(DAILY.replace("071000", "072000"));
        baseUrl = "https://kis.test";
        assertEquals("071000", provider.getDailyMinutes(request()).get(2).getClosePrice());
        baseUrl = "https://virtual.kis.test";
        assertEquals("072000", provider.getDailyMinutes(request()).get(2).getClosePrice());
        baseUrl = "https://kis.test";
        assertEquals("071000", provider.getDailyMinutes(request()).get(2).getClosePrice());
        baseUrl = "https://virtual.kis.test";
        assertEquals("072000", provider.getDailyMinutes(request()).get(2).getClosePrice());
        assertEquals(2, redis.ttls.size());
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        server.verify();
    }

    @Test
    public void legacyAndOtherProviderKeysAreIgnoredAndLeftToExpire() {
        List<Map<String, String>> obsolete = List.of(Map.of("stck_prpr", "obsolete"));
        String oldKey = "stream:minute:" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ":005930";
        String otherKey = cacheKey().replaceFirst("kis:", "kiwoom:");
        redis.values.put(oldKey, obsolete);
        redis.values.put(otherKey, obsolete);
        expectDaily(DAILY);
        assertEquals("071000", provider.getDailyMinutes(request()).get(2).getClosePrice());
        assertEquals(obsolete, redis.values.get(oldKey));
        assertEquals(obsolete, redis.values.get(otherKey));
        assertEquals(1, redis.ttls.size());
        assertEquals(Long.valueOf(60), redis.ttls.get(cacheKey()));
        server.verify();
    }

    @Test
    public void delimitersNullAndEmptyComponentsCannotAliasInRepositoryKeys() {
        List<StreamMinutePriceRequest> requests = List.of(
                dailyRequest("A:B", "C", "153000", "20261021", "N", ""),
                dailyRequest("A", "B:C", "153000", "20261021", "N", ""),
                dailyRequest(null, "005930", "153000", "20261021", "N", ""),
                dailyRequest("", "005930", "153000", "20261021", "N", ""),
                dailyRequest("null", "005930", "153000", "20261021", "N", ""));
        for (int i = 0; i < requests.size(); i++) {
            StreamMinutePrice price = new StreamMinutePrice();
            price.setStckPrpr(String.valueOf(i));
            charts.saveStreamMinute(baseUrl, requests.get(i), List.of(price));
        }
        for (int i = 0; i < requests.size(); i++) {
            assertEquals(String.valueOf(i), charts.getStreamMinuteChartData(baseUrl, requests.get(i))
                    .get().get(0).getStckPrpr());
        }
        assertEquals(requests.size(), redis.ttls.size());
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
        assertThrows(RateLimitExceededException.class, () -> provider.getRecentMinutes("005930"));
        assertEquals(3, provider.getDailyMinutes(request()).size());
        server.verify();
    }

    @Test
    public void recentKeepsCurrentServerTimeNumericDefaultsAndOrderWhileConsumingEachCall() {
        useFixedBudget(2);
        LocalTime before = LocalTime.now().withNano(0);
        expectRecent(RECENT, before);
        expectRecent(RECENT, before);
        List<RecentMinuteCandle> expected = Arrays.asList(
                new RecentMinuteCandle("153000", 71000, 70000, 72000, 69000),
                new RecentMinuteCandle("152900", 0, 0, 0, 0), null);
        assertEquals(expected, provider.getRecentMinutes("005930"));
        assertEquals(1, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        assertEquals(expected, provider.getRecentMinutes("005930"));
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void recentRejectsExhaustedBudgetBeforeTokenLookupOrHttp() {
        useFixedBudget(0);
        redis.values.clear();
        assertThrows(RateLimitExceededException.class, () -> provider.getRecentMinutes("005930"));
        assertEquals(0, redis.reads);
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void recentSharesBudgetWithCurrentPriceAndDailyMinutes() {
        useFixedBudget(1);
        expectRecent(RECENT, LocalTime.now().withNano(0));
        provider.getRecentMinutes("005930");
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        assertThrows(RateLimitExceededException.class, () -> legacy.getCurrentPrice(
                CurrentPriceRequest.builder().stockCode("000660").marketCode("J").build()));
        assertThrows(RateLimitExceededException.class, () -> provider.getDailyMinutes(request()));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void recentRealServiceReturnsExisting429BodyAndRetryAfterWithoutHttp() throws Exception {
        useFixedBudget(0);
        MockHttpServletResponse response = MockMvcBuilders.standaloneSetup(
                new KisController(null, null, null, provider, null, null))
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/api/kis/candle/005930")).andReturn().getResponse();
        assertEquals(429, response.getStatus());
        JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertEquals(3, body.size());
        assertEquals("Rate limit exceeded", body.get("error").asText());
        assertEquals("한국투자증권 API 호출 한도 초과. 잠시 후 다시 시도해주세요.", body.get("message").asText());
        assertTrue(body.get("retryAfter").asLong() > 0);
        assertEquals(body.get("retryAfter").asText(), response.getHeader("Retry-After"));
        assertEquals(0, redis.reads);
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void recentKeepsNullOrEmptyOutputAndMissingBodyFailure() {
        useFixedBudget(4);
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
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void bothRemoteFailuresPropagateWithoutRetryOrCacheWrite() {
        useFixedBudget(2);
        server.expect(req -> assertDailyUrl(req.getURI().toString(), "J", "153000", "N", "", "20261021"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        LocalTime before = LocalTime.now().withNano(0);
        server.expect(req -> assertRecentUrl(req.getURI().toString(), before))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(KisMarketDataException.class, () -> provider.getDailyMinutes(request()));
        assertEquals(1, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        assertThrows(KisMarketDataException.class, () -> provider.getRecentMinutes("005930"));
        assertEquals(0, limiter.getAvailableTokens(KisRateLimitTestSupport.scope(baseUrl)));
        assertThrows(RateLimitExceededException.class, () -> provider.getRecentMinutes("005930"));
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
        return cacheKey(baseUrl, dailyRequest());
    }

    private String cacheKey(String endpoint, StreamMinutePriceRequest request) {
        StringBuilder key = new StringBuilder("kis:chart:stream-minute:v2");
        for (String value : new String[]{endpoint, request.getCondMrktDivCode(), request.getInputIscd(),
                request.getInputHour1(), request.getInputDate1(), request.getPwDataIncuYn(), request.getFakeTickIncuYn()}) {
            key.append(':').append(value == null ? -1 : value.length()).append(':');
            if (value != null) key.append(value);
        }
        return key.toString();
    }

    private StreamMinutePriceRequest dailyRequest() {
        return dailyRequest("J", "005930", "153000", "20261021", "N", "");
    }

    private StreamMinutePriceRequest dailyRequest(String market, String stock, String time, String date,
                                                String past, String fake) {
        return StreamMinutePriceRequest.builder().condMrktDivCode(market).inputIscd(stock).inputHour1(time)
                .inputDate1(date).pwDataIncuYn(past).fakeTickIncuYn(fake).build();
    }

    private void expectDailyRequest(StreamMinutePriceRequest request, String json) {
        String endpoint = baseUrl;
        server.expect(req -> {
            assertEquals(endpoint + "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice"
                    + "?FID_COND_MRKT_DIV_CODE=" + request.getCondMrktDivCode()
                    + "&FID_INPUT_ISCD=" + request.getInputIscd() + "&FID_INPUT_HOUR_1=" + request.getInputHour1()
                    + "&FID_INPUT_DATE_1=" + request.getInputDate1() + "&FID_PW_DATA_INCU_YN=" + request.getPwDataIncuYn()
                    + "&FID_FAKE_TICK_INCU_YN=" + request.getFakeTickIncuYn(), req.getURI().toString());
            assertHeaders(req.getMethod(), req.getHeaders(), "FHKST03010230");
            assertEquals("N", req.getHeaders().getFirst("tr_cont"));
        }).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void expectDaily(String json) {
        expectDailyRequest(dailyRequest(), json);
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
        int capacity = Math.max(1, remaining);
        Bucket budget = Bucket.builder().addLimit(Bandwidth.builder().capacity(capacity)
                .refillIntervally(capacity, Duration.ofDays(1)).build()).build();
        if (remaining == 0) budget.tryConsume(1);
        KisRateLimitTestSupport.fixedCacheBudget(limiter, budget);
    }

    private static class MemoryRedis extends RedisTemplate<String, Object> {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        final Map<String, Long> ttls = new ConcurrentHashMap<>();
        int reads;

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, Object> opsForValue() {
            return (ValueOperations<String, Object>) Proxy.newProxyInstance(ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class}, (proxy, method, args) -> {
                        if ("get".equals(method.getName())) {
                            reads++;
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
