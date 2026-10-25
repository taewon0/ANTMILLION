package com.antmillion.kiwoom;

import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomMarketDataException;
import com.antmillion.kiwoom.exception.KiwoomMarketDataException.Kind;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.*;
import com.antmillion.kiwoom.service.*;
import com.antmillion.marketdata.dto.*;
import io.github.bucket4j.TimeMeter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 adapter·HTTP와 메모리 토큰/캐시·고정 시각만 사용하며 외부 자원을 로드하지 않습니다. */
@RunWith(Parameterized.class)
public class KiwoomMarketDataErrorTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> operations() {
        return Arrays.asList(new Object[][]{{"current"}, {"period-D"}, {"period-W"}, {"period-M"},
                {"period-Y"}, {"daily"}, {"recent"}, {"index"}, {"value"}, {"volume"}, {"flow"}});
    }

    private static final LocalDate DATE = LocalDate.of(2026, 10, 23);
    private final String operation;
    private final KiwoomTokenServiceTest.MutableClock clock = new KiwoomTokenServiceTest.MutableClock();
    private final KiwoomTokenServiceTest.MemoryRedis redis = new KiwoomTokenServiceTest.MemoryRedis(clock);
    private final KiwoomTokenServiceTest.LockClient locks = new KiwoomTokenServiceTest.LockClient();
    private final RestTemplate rest = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
    private final KiwoomAccessTokenScope scope = new KiwoomAccessTokenScope("virtual", "synthetic-key", "synthetic-secret");
    private KiwoomTokenService tokens;
    private KiwoomRestRateLimiter limiter;

    public KiwoomMarketDataErrorTest(String operation) { this.operation = operation; }

    @Before
    public void setUp() {
        KiwoomAccessTokenRedisRepository saved = new KiwoomAccessTokenRedisRepository(redis, clock);
        saved.save(scope, "synthetic-token", clock.instant().plusSeconds(86400), 600);
        tokens = new KiwoomTokenService(scope, 600, rest, saved, locks.client(), clock);
        limiter = KiwoomRestRateLimitTest.withClock(new TimeMeter() {
            @Override public long currentTimeNanos() { return 0; }
            @Override public boolean isWallClockBased() { return false; }
        });
    }

    @Test
    public void unauthorizedIsAuthenticationWithoutTokenInvalidationOrRetry() {
        httpFailure(HttpStatus.UNAUTHORIZED, Kind.AUTHENTICATION);
    }

    @Test
    public void forbiddenIsAuthentication() { httpFailure(HttpStatus.FORBIDDEN, Kind.AUTHENTICATION); }

    @Test
    public void remoteRateLimitIsSeparateFromLocalRejection() {
        httpFailure(HttpStatus.TOO_MANY_REQUESTS, Kind.REMOTE_RATE_LIMIT);
    }

    @Test
    public void serverErrorIsServerFailure() { httpFailure(HttpStatus.INTERNAL_SERVER_ERROR, Kind.SERVER); }

    @Test
    public void serviceUnavailableIsServerFailure() { httpFailure(HttpStatus.SERVICE_UNAVAILABLE, Kind.SERVER); }

    @Test
    public void otherClientStatusIsNotAuthenticationOrBusiness() {
        httpFailure(HttpStatus.BAD_REQUEST, Kind.HTTP_ERROR);
    }

    @Test
    public void timeoutIsTransportWithoutRawCause() {
        expect().andRespond(request -> { throw new SocketTimeoutException("synthetic-sensitive-timeout"); });
        failure(Kind.TRANSPORT, null, null);
    }

    @Test
    public void ioFailureIsTransport() {
        expect().andRespond(request -> { throw new IOException("synthetic-sensitive-connection"); });
        failure(Kind.TRANSPORT, null, null);
    }

    @Test
    public void unclassifiedClientFailureDoesNotPretendToBeRemoteOrTransportFailure() {
        expect().andRespond(request -> { throw new RestClientException("synthetic-sensitive-client"); });
        failure(Kind.CLIENT_ERROR, null, null);
    }

    @Test
    public void malformedJsonIsInvalidResponse() {
        expect().andRespond(withSuccess("{\"synthetic-sensitive\":", MediaType.APPLICATION_JSON));
        failure(Kind.INVALID_RESPONSE, null, null);
    }

    @Test
    public void unsupportedResponseContentTypeIsInvalidResponse() {
        expect().andRespond(withSuccess("synthetic-sensitive-body", MediaType.TEXT_PLAIN));
        failure(Kind.INVALID_RESPONSE, null, null);
    }

    @Test
    public void businessFailureRetainsOnlyNumericCodeAndTakesPriorityOverMissingFields() {
        expect().andRespond(withSuccess("{\"return_code\":10,\"return_msg\":\"synthetic-sensitive-body\"}",
                MediaType.APPLICATION_JSON));
        failure(Kind.BUSINESS, null, 10);
    }

    @Test
    public void businessCodeIsNotInterpretedAsHttpStatus() {
        expect().andRespond(withSuccess("{\"return_code\":429}", MediaType.APPLICATION_JSON));
        failure(Kind.BUSINESS, null, 429);
    }

    @Test
    public void emptyBodyIsInvalidResponse() {
        expect().andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        failure(Kind.INVALID_RESPONSE, null, null);
    }

    @Test
    public void invalidNormalizedPayloadIsInvalidResponse() {
        String body;
        if (operation.equals("current")) body = "{\"cur_prc\":\"synthetic-sensitive-price\"}";
        else if (operation.startsWith("period-")) body = "{\"stk_cd\":\"000660\"}";
        else if (operation.equals("daily") || operation.equals("recent")) body = "{\"stk_cd\":\"000660\"}";
        else if (operation.equals("index")) body = "{\"inds_cd\":\"101\"}";
        else if (operation.equals("flow")) body = "{\"opmr_invsr_trde_chart\":[{\"tm\":\"synthetic-sensitive-time\"}]}";
        else body = "{\"tdy_trde_qty_upper\":[{\"stk_cd\":\"synthetic-sensitive-code\"}]}";
        expect().andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        failure(Kind.INVALID_RESPONSE, null, null);
    }

    @Test
    public void tokenStorageFailureIsNotMisclassifiedAsRemoteAuthentication() {
        redis.failReads = true;
        failure(Kind.TOKEN_ACCESS, null, null);
    }

    @Test
    public void failedHttpStillConsumesBudgetAndLocalRejectionDoesNotCallTokenOrHttp() {
        expect().andRespond(withServerError());
        failure(Kind.SERVER, 500, null);
        long tokenReads = redis.reads.stream().filter(scope.getTokenKey()::equals).count();
        KiwoomRateLimitExceededException rejected = assertThrows(KiwoomRateLimitExceededException.class, this::query);
        assertEquals(1, rejected.getRetryAfterSeconds());
        assertEquals(tokenReads, redis.reads.stream().filter(scope.getTokenKey()::equals).count());
        assertEquals(1, redis.writes.get());
        server.verify();
    }

    private void httpFailure(HttpStatus status, Kind kind) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Sensitive", "synthetic-sensitive-header");
        expect().andRespond(withStatus(status).body("synthetic-sensitive-body")
                .headers(headers).contentType(MediaType.APPLICATION_JSON));
        failure(kind, status.value(), null);
    }

    private void failure(Kind kind, Integer status, Integer code) {
        KiwoomMarketDataException error = assertThrows(KiwoomMarketDataException.class, this::query);
        assertEquals(kind, error.getKind());
        assertEquals(status, error.getHttpStatus());
        assertEquals(code, error.getReturnCode());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        StringWriter output = new StringWriter();
        error.printStackTrace(new PrintWriter(output));
        for (String secret : Arrays.asList("synthetic-sensitive", "synthetic-token", "synthetic-key", "synthetic-secret")) {
            assertFalse(output.toString().contains(secret));
        }
        assertEquals(1, redis.writes.get()); // 오류는 캐시에 저장하지 않고 기존 토큰도 삭제하지 않습니다.
        assertEquals(1, redis.values.size());
        assertTrue(locks.keys.isEmpty());
        server.verify(); // 등록한 단일 시세 요청 외 발급·재시도는 허용하지 않습니다.
    }

    private ResponseActions expect() {
        String path = operation.equals("current") ? "/api/dostk/stkinfo"
                : operation.equals("value") || operation.equals("volume") ? "/api/dostk/rkinfo" : "/api/dostk/chart";
        String apiId = operation.equals("current") ? "ka10001" : operation.equals("index") ? "ka20006"
                : operation.equals("value") || operation.equals("volume") ? "ka10030"
                : operation.equals("flow") ? "ka10064"
                : operation.equals("daily") || operation.equals("recent") ? "ka10080"
                : operation.equals("period-D") ? "ka10081" : operation.equals("period-W") ? "ka10082"
                : operation.equals("period-M") ? "ka10083" : "ka10094";
        return server.expect(requestTo(scope.getBaseUrl() + path)).andExpect(method(HttpMethod.POST))
                .andExpect(header("api-id", apiId)).andExpect(header("Authorization", "Bearer synthetic-token"));
    }

    private Object query() {
        if (operation.equals("current")) {
            return new KiwoomCurrentPriceProvider(tokens, rest, limiter,
                    new KiwoomCurrentPriceRedisRepository(redis, clock), com.antmillion.marketdata.CircuitBreakerTestSupport.defaults()).getCurrentPrice("005930");
        }
        if (operation.startsWith("period-")) {
            return new KiwoomPeriodChartProvider(tokens, rest, limiter,
                    new KiwoomPeriodChartRedisRepository(redis, clock), com.antmillion.marketdata.CircuitBreakerTestSupport.defaults())
                    .getPeriodChart(new PeriodChartRequest("005930", operation.substring(7), DATE.minusDays(10), DATE));
        }
        if (operation.equals("daily") || operation.equals("recent")) {
            KiwoomMinuteChartProvider provider = new KiwoomMinuteChartProvider(tokens, rest, limiter,
                    new KiwoomDailyMinuteRedisRepository(redis, clock), com.antmillion.marketdata.CircuitBreakerTestSupport.defaults(), clock);
            return operation.equals("daily") ? provider.getDailyMinutes(new MinuteChartRequest("005930", DATE))
                    : provider.getRecentMinutes("005930");
        }
        if (operation.equals("index")) {
            return new KiwoomMarketIndexChartProvider(tokens, rest, limiter,
                    new KiwoomMarketIndexChartRedisRepository(redis, clock), com.antmillion.marketdata.CircuitBreakerTestSupport.defaults())
                    .getMarketIndexChart(new MarketIndexChartRequest("0001", DATE));
        }
        if (operation.equals("flow")) {
            return new KiwoomInvestorFlowProvider(tokens, rest, limiter,
                    new KiwoomInvestorFlowRedisRepository(redis, clock)).getInvestorFlow("005930");
        }
        KiwoomStockRankingProvider provider = new KiwoomStockRankingProvider(tokens, rest, limiter,
                new KiwoomStockRankingRedisRepository(redis, clock));
        return operation.equals("value") ? provider.getTradingValueRanks() : provider.getVolumeRanks();
    }
}
