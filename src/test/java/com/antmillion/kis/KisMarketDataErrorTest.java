package com.antmillion.kis;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.constant.KisApiConstant;
import com.antmillion.kis.dto.CurrentPriceRequest;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.exception.KisMarketDataException;
import com.antmillion.kis.exception.KisMarketDataException.Kind;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.repository.*;
import com.antmillion.kis.service.*;
import com.antmillion.marketdata.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 시세 HTTP 8개 경로에 합성 인증·메모리 Redis/잠금·고정 예산만 연결합니다. */
@RunWith(Parameterized.class)
public class KisMarketDataErrorTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> operations() {
        return Arrays.asList(new Object[][]{{"current"}, {"detail"}, {"period"}, {"daily"},
                {"recent"}, {"index"}, {"ranking"}, {"flow"}});
    }

    private final String operation;
    private final KisAccessTokenScope scope = new KisAccessTokenScope("https://kis.test", "synthetic-key", "synthetic-secret");
    private final MemoryRedis redis = new MemoryRedis();
    private final RestTemplate rest = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
    private KisApiService service;
    private RateLimitedKisApiClient limiter;
    private Bucket budget;
    private boolean failLock;
    private int lockCalls;
    private int unlocks;

    public KisMarketDataErrorTest(String operation) { this.operation = operation; }

    @Before
    public void setUp() {
        redis.values.put(scope.getTokenKey(), "synthetic-token");
        KisConfig config = new KisConfig() {
            @Override public String getBaseUrl() { return scope.getBaseUrl(); }
        };
        config.appKey = "synthetic-key";
        config.appSecret = "synthetic-secret";
        config.accountNo = "synthetic-account";
        config.acntPrdtCd = "01";
        limiter = new RateLimitedKisApiClient(redis, config, new KisRestRateLimitConfig(18, 1));
        budget = Bucket.builder().addLimit(Bandwidth.builder().capacity(1)
                .refillIntervally(1, Duration.ofDays(1)).build()).build();
        KisRateLimitTestSupport.fixedCacheBudget(limiter, budget);
        RLock lock = (RLock) Proxy.newProxyInstance(RLock.class.getClassLoader(), new Class<?>[]{RLock.class},
                (proxy, method, args) -> {
                    if ("tryLock".equals(method.getName())) {
                        if (failLock) throw new IllegalStateException("synthetic-sensitive-lock");
                        return true;
                    }
                    if ("isHeldByCurrentThread".equals(method.getName())) return !failLock;
                    if ("unlock".equals(method.getName())) { unlocks++; return null; }
                    throw new AssertionError("예상하지 않은 잠금 호출: " + method.getName());
                });
        RedissonClient locks = (RedissonClient) Proxy.newProxyInstance(RedissonClient.class.getClassLoader(),
                new Class<?>[]{RedissonClient.class}, (proxy, method, args) -> {
                    if ("getLock".equals(method.getName())) { lockCalls++; return lock; }
                    throw new AssertionError("예상하지 않은 잠금 client 호출");
                });
        ObjectMapper mapper = new ObjectMapper();
        service = new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis),
                new KisChartRedisRepository(redis, mapper), new KisMarketIndexChartRedisRepository(redis, mapper),
                new KisStockVolumeRankRedisRepository(redis, mapper), new KisFrgnOrgnRedisRepository(redis, mapper),
                locks, limiter);
    }

    @Test
    public void unauthorizedIsAuthenticationWithoutInvalidationOrRetry() { httpFailure(HttpStatus.UNAUTHORIZED, Kind.AUTHENTICATION); }

    @Test
    public void forbiddenIsAuthentication() { httpFailure(HttpStatus.FORBIDDEN, Kind.AUTHENTICATION); }

    @Test
    public void remoteRateLimitIsSeparateFromLocalRejection() { httpFailure(HttpStatus.TOO_MANY_REQUESTS, Kind.REMOTE_RATE_LIMIT); }

    @Test
    public void internalServerErrorIsServerFailure() { httpFailure(HttpStatus.INTERNAL_SERVER_ERROR, Kind.SERVER); }

    @Test
    public void serviceUnavailableIsServerFailure() { httpFailure(HttpStatus.SERVICE_UNAVAILABLE, Kind.SERVER); }

    @Test
    public void otherHttpStatusIsNotAuthenticationOrBusiness() { httpFailure(HttpStatus.BAD_REQUEST, Kind.HTTP_ERROR); }

    @Test
    public void timeoutIsTransportWithoutRawCause() {
        expect().andRespond(request -> { throw new SocketTimeoutException("synthetic-sensitive-timeout"); });
        failure(Kind.TRANSPORT, null, null);
    }

    @Test
    public void connectionFailureIsTransport() {
        expect().andRespond(request -> { throw new IOException("synthetic-sensitive-connection"); });
        failure(Kind.TRANSPORT, null, null);
    }

    @Test
    public void unclassifiedClientFailureRemainsSeparate() {
        expect().andRespond(request -> { throw new RestClientException("synthetic-sensitive-client"); });
        failure(Kind.CLIENT_ERROR, null, null);
    }

    @Test
    public void malformedJsonIsInvalidResponse() {
        expect().andRespond(withSuccess("{\"synthetic-sensitive\":", MediaType.APPLICATION_JSON));
        failure(Kind.INVALID_RESPONSE, null, null);
    }

    @Test
    public void unsupportedContentTypeIsInvalidResponse() {
        expect().andRespond(withSuccess("synthetic-sensitive-body", MediaType.TEXT_PLAIN));
        failure(Kind.INVALID_RESPONSE, null, null);
    }

    @Test
    public void emptyBodyIsInvalidResponse() {
        expect().andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        failure(Kind.INVALID_RESPONSE, null, null);
    }

    @Test
    public void incompatibleOutputTypeIsInvalidResponse() {
        String body = operation.equals("current") || operation.equals("detail") ? "{\"output\":[]}"
                : operation.equals("ranking") ? "{\"output\":{}}" : "{\"output2\":{}}";
        expect().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        failure(Kind.INVALID_RESPONSE, null, null);
    }

    @Test
    public void incompatibleReturnCodeTypeIsInvalidResponse() {
        expect().andRespond(withSuccess(withCode("[]"), MediaType.APPLICATION_JSON));
        failure(Kind.INVALID_RESPONSE, null, null);
    }

    @Test
    public void jsonNullBodyIsInvalidResponse() {
        expect().andRespond(withSuccess("null", MediaType.APPLICATION_JSON));
        failure(Kind.INVALID_RESPONSE, null, null);
    }

    @Test
    public void businessCodeTakesPriorityOverMissingOutput() {
        expect().andRespond(withSuccess("{\"rt_cd\":\"1\",\"msg_cd\":\"synthetic-sensitive-code\","
                + "\"msg1\":\"synthetic-sensitive-body\"}", MediaType.APPLICATION_JSON));
        failure(Kind.BUSINESS, null, 1);
    }

    @Test
    public void businessCodeIsNotAnHttpStatusEvenWithUsableOutput() {
        expect().andRespond(withSuccess(withCode("\"429\""), MediaType.APPLICATION_JSON));
        failure(Kind.BUSINESS, null, 429);
    }

    @Test
    public void arbitraryReturnCodeIsNotRetainedInException() {
        expect().andRespond(withSuccess(withCode("\"synthetic-sensitive-return-code\""), MediaType.APPLICATION_JSON));
        failure(Kind.BUSINESS, null, null);
    }

    @Test
    public void outOfRangeReturnCodeDoesNotLeakOrFailParsing() {
        expect().andRespond(withSuccess(withCode("\"9999999999\""), MediaType.APPLICATION_JSON));
        failure(Kind.BUSINESS, null, null);
    }

    @Test
    public void missingReturnCodePreservesSuccessfulPayload() { success(successBody()); }

    @Test
    public void nullReturnCodePreservesSuccessfulPayload() { success(withCode("null")); }

    @Test
    public void emptyReturnCodePreservesSuccessfulPayload() { success(withCode("\"\"")); }

    @Test
    public void zeroReturnCodePreservesSuccessfulPayload() { success(withCode("\"0\"")); }

    @Test
    public void tokenStorageFailureIsNotRemoteAuthentication() {
        redis.failTokenReads = true;
        failure(Kind.TOKEN_ACCESS, null, null);
    }

    @Test
    public void tokenLockFailureDoesNotCallHttp() {
        redis.values.clear();
        failLock = true;
        failure(Kind.TOKEN_ACCESS, null, null);
        assertEquals(1, lockCalls);
        assertEquals(0, unlocks);
    }

    @Test
    public void tokenIssuanceUnauthorizedIsTokenAccessAndReleasesLock() {
        redis.values.clear();
        server.expect(requestTo(scope.getBaseUrl() + KisApiConstant.OAUTH_TOKEN_PATH)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("synthetic-sensitive-token-body"));
        failure(Kind.TOKEN_ACCESS, null, null);
        assertEquals(1, unlocks);
    }

    @Test
    public void tokenSaveFailureIsTokenAccessAndReleasesLock() {
        redis.values.clear();
        redis.failTokenWrites = true;
        server.expect(requestTo(scope.getBaseUrl() + KisApiConstant.OAUTH_TOKEN_PATH)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"synthetic-new-token\",\"expires_in\":86400}", MediaType.APPLICATION_JSON));
        failure(Kind.TOKEN_ACCESS, null, null);
        assertEquals(1, unlocks);
    }

    @Test
    public void failedHttpConsumesBudgetAndLocalRejectionDoesNotReadTokenOrCallHttp() {
        expect().andRespond(withServerError());
        failure(Kind.SERVER, 500, null);
        int reads = redis.tokenReads;
        assertThrows(RateLimitExceededException.class, this::query);
        assertEquals(reads, redis.tokenReads);
        assertEquals(0, budget.getAvailableTokens());
        server.verify();
    }

    private void success(String body) {
        expect().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertNotNull(query());
        assertEquals(0, budget.getAvailableTokens());
        assertEquals(0, lockCalls);
        server.verify();
    }

    private void httpFailure(HttpStatus status, Kind kind) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Sensitive", "synthetic-sensitive-header");
        expect().andRespond(withStatus(status).body("synthetic-sensitive-body").headers(headers));
        failure(kind, status.value(), null);
    }

    private void failure(Kind kind, Integer status, Integer code) {
        KisMarketDataException error = assertThrows(KisMarketDataException.class, this::query);
        assertEquals(kind, error.getKind());
        assertEquals(status, error.getHttpStatus());
        assertEquals(code, error.getReturnCode());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        for (String secret : Arrays.asList("synthetic-sensitive", "synthetic-token", "synthetic-new-token",
                "synthetic-key", "synthetic-secret", "synthetic-account")) {
            assertFalse(stack.toString().contains(secret));
        }
        assertEquals(0, redis.writes);
        assertTrue(redis.values.keySet().stream().allMatch(scope.getTokenKey()::equals));
        assertEquals(0, budget.getAvailableTokens());
        server.verify();
    }

    private ResponseActions expect() {
        String path = operation.equals("current") || operation.equals("detail") ? KisApiConstant.PRESENT_PRICE
                : operation.equals("period") ? KisApiConstant.PERIOD_PRICE_PATH
                : operation.equals("daily") ? KisApiConstant.STREAM_MINUTE_PATH
                : operation.equals("recent") ? KisApiConstant.DAY_MINUTE_PATH
                : operation.equals("index") ? KisApiConstant.MARKET_INDEX_PATH
                : operation.equals("ranking") ? KisApiConstant.STOCK_VOLUME_RANK : KisApiConstant.FOREIGNER_ORGANIZATION_PATH;
        String trId = operation.equals("current") || operation.equals("detail") ? "FHKST01010100"
                : operation.equals("period") ? "FHKST03010100" : operation.equals("daily") ? "FHKST03010230"
                : operation.equals("recent") ? "FHKST03010200" : operation.equals("index") ? "FHPUP02120000"
                : operation.equals("ranking") ? "FHPST01710000" : "HHPTJ04160200";
        return server.expect(request -> assertEquals(scope.getBaseUrl() + path,
                        request.getURI().toString().split("\\?", 2)[0]))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer synthetic-token"))
                .andExpect(header("tr_id", trId));
    }

    private String withCode(String code) { return "{\"rt_cd\":" + code + "," + successBody().substring(1); }

    private String successBody() {
        return operation.equals("current") || operation.equals("detail")
                ? "{\"output\":{\"stck_prpr\":\"71000\",\"prdy_ctrt\":\"-1.20\"}}"
                : operation.equals("ranking") ? "{\"output\":[]}" : "{\"output2\":[]}";
    }

    private Object query() {
        LocalDate date = LocalDate.of(2026, 10, 23);
        if (operation.equals("current")) return new KisCurrentPriceProvider(service).getCurrentPrice("005930");
        if (operation.equals("detail")) return service.getCurrentPriceDetail(CurrentPriceRequest.builder()
                .marketCode("J").stockCode("005930").build());
        if (operation.equals("period")) return new KisPeriodChartProvider(service)
                .getPeriodChart(new PeriodChartRequest("005930", "D", date.minusDays(10), date));
        if (operation.equals("daily")) return new KisMinuteChartProvider(service).getDailyMinutes(new MinuteChartRequest("005930", date));
        if (operation.equals("recent")) return new KisMinuteChartProvider(service).getRecentMinutes("005930");
        if (operation.equals("index")) return new KisMarketIndexChartProvider(service).getMarketIndexChart(new MarketIndexChartRequest("0001", date));
        if (operation.equals("ranking")) return new KisStockRankingProvider(service).getTradingValueRanks();
        return new KisInvestorFlowProvider(service).getInvestorFlow("005930");
    }

    private class MemoryRedis extends RedisTemplate<String, Object> {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        int tokenReads;
        int writes;
        boolean failTokenReads;
        boolean failTokenWrites;

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, Object> opsForValue() {
            return (ValueOperations<String, Object>) Proxy.newProxyInstance(ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class}, (proxy, method, args) -> {
                        boolean tokenKey = scope.getTokenKey().equals(args[0]);
                        if ("get".equals(method.getName())) {
                            if (tokenKey) {
                                tokenReads++;
                                if (failTokenReads) throw new IllegalStateException("synthetic-sensitive-token-store");
                            }
                            return values.get(args[0]);
                        }
                        if ("set".equals(method.getName()) && args.length == 4) {
                            if (tokenKey && failTokenWrites) throw new IllegalStateException("synthetic-sensitive-token-write");
                            values.put((String) args[0], args[1]);
                            writes++;
                            return null;
                        }
                        throw new AssertionError("예상하지 않은 Redis 호출: " + method.getName());
                    });
        }
    }
}
