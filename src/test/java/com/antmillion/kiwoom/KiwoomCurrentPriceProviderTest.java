package com.antmillion.kiwoom;

import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.service.KiwoomCurrentPriceProvider;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.marketdata.dto.CurrentQuote;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 adapter·토큰 서비스에 고정 시각·메모리 Redis·잠금·HTTP 대역만 연결합니다. */
public class KiwoomCurrentPriceProviderTest {
    private KiwoomTokenServiceTest.MutableClock clock;
    private KiwoomTokenServiceTest.MemoryRedis redis;
    private KiwoomTokenServiceTest.LockClient locks;
    private KiwoomAccessTokenRedisRepository repository;
    private RestTemplate rest;
    private MockRestServiceServer server;
    private CurrentPriceProvider provider;
    private KiwoomAccessTokenScope scope;

    @Before
    public void setUp() {
        clock = new KiwoomTokenServiceTest.MutableClock();
        redis = new KiwoomTokenServiceTest.MemoryRedis(clock);
        locks = new KiwoomTokenServiceTest.LockClient();
        repository = new KiwoomAccessTokenRedisRepository(redis, clock);
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        scope = scope("virtual");
        cacheToken(scope, "synthetic-token");
        provider = provider(scope);
    }

    @Test
    public void singleUsesKrxPostApiIdBearerAndOnlyStockCodeBody() {
        expect(scope, "005930", "synthetic-token").andRespond(withSuccess(
                "{\"stk_cd\":\"005930\",\"cur_prc\":\"+071000\",\"flu_rt\":\"+1.25\","
                        + "\"stk_nm\":\"synthetic-name\",\"return_code\":0}", MediaType.APPLICATION_JSON));
        assertEquals(Integer.valueOf(71000), provider.getCurrentPrice("005930"));
        assertEquals(1, redis.writes.get()); // 준비한 토큰 외에 시세 캐시는 저장하지 않습니다.
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void fallingPriceBecomesPositiveWonAndRateKeepsSignAndPrecision() {
        quote("005930", "-071000", "-01.20");
        assertEquals(List.of(new CurrentQuote("005930", "71000", "-01.20")),
                provider.getCurrentPrices(List.of("005930")));
        server.verify();
    }

    @Test
    public void integralDecimalPriceAndZeroAreExactWithoutRounding() {
        quote("005930", "+71000.00", "+0.00");
        quote("000660", "-0.00", "0");
        assertEquals(List.of(new CurrentQuote("005930", "71000", "+0.00"),
                new CurrentQuote("000660", "0", "0")),
                provider.getCurrentPrices(List.of("005930", "000660")));
        server.verify();
    }

    @Test
    public void singleMissingAndBlankPriceReturnNullWithoutInventingZero() {
        for (String value : Arrays.asList(null, "", "  ")) {
            server.reset();
            quote("005930", value, null);
            assertNull(provider.getCurrentPrice("005930"));
            server.verify();
        }
    }

    @Test
    public void listPreservesMissingFieldsAndTrimsBlankFields() {
        quote("005930", null, null);
        quote("000660", "  ", " ");
        assertEquals(List.of(new CurrentQuote("005930", null, null), new CurrentQuote("000660", "", "")),
                provider.getCurrentPrices(List.of("005930", "000660")));
        server.verify();
    }

    @Test
    public void invalidFractionalAndOverflowPricesFailWithoutRawValueOrCause() {
        for (String price : List.of("sensitive-payload", "1.01", "-2147483648", "2147483648", "1e3", "1,000")) {
            server.reset();
            quote("005930", price, "0");
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> provider.getCurrentPrice("005930"));
            assertFalse(error.getMessage().contains(price));
            assertNull(error.getCause());
            server.verify();
        }
    }

    @Test
    public void maximumIntegerPriceIsAccepted() {
        quote("005930", "-2147483647", "0");
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), provider.getCurrentPrice("005930"));
        server.verify();
    }

    @Test
    public void invalidRateOmitsOnlyThatListItemAndDoesNotBreakPriceOnlyLookup() {
        quote("005930", "71000", "sensitive-rate");
        quote("000660", "120000", "+2.30");
        quote("005930", "71000", "sensitive-rate");
        assertEquals(List.of(new CurrentQuote("000660", "120000", "+2.30")),
                provider.getCurrentPrices(List.of("005930", "000660")));
        assertEquals(Integer.valueOf(71000), provider.getCurrentPrice("005930"));
        server.verify();
    }

    @Test
    public void listKeepsOrderDuplicatesAndContinuesAfterRemoteOrConversionFailure() {
        quote("005930", "100", "1");
        expect(scope, "000660", "synthetic-token").andRespond(withServerError());
        quote("035420", "bad", "0");
        quote("005930", "200", "2");
        assertEquals(List.of(new CurrentQuote("005930", "100", "1"), new CurrentQuote("005930", "200", "2")),
                provider.getCurrentPrices(List.of("005930", "000660", "035420", "005930")));
        server.verify();
    }

    @Test
    public void emptyListAndInvalidKrxCodesDoNotReadTokenOrCallHttp() {
        assertTrue(provider.getCurrentPrices(List.of()).isEmpty());
        for (String code : Arrays.asList(null, "", "00593", "005930_NX", "005930_AL", "A005930", " 005930")) {
            assertThrows(IllegalArgumentException.class, () -> provider.getCurrentPrice(code));
        }
        assertTrue(provider.getCurrentPrices(Arrays.asList(null, "005930_NX", "bad")).isEmpty());
        assertTrue(redis.reads.isEmpty());
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void krxAlphanumericShortCodeIsNotRewritten() {
        quote("00AB12", "1000", "0");
        assertEquals(Integer.valueOf(1000), provider.getCurrentPrice("00AB12"));
        server.verify();
    }

    @Test
    public void responseCodeMismatchFailsAndMissingCodeUsesRequestedCode() {
        expect(scope, "005930", "synthetic-token").andRespond(withSuccess(
                "{\"stk_cd\":\"000660\",\"cur_prc\":\"100\"}", MediaType.APPLICATION_JSON));
        expect(scope, "005930", "synthetic-token").andRespond(withSuccess(
                "{\"cur_prc\":\"200\",\"flu_rt\":\"0\"}", MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, () -> provider.getCurrentPrice("005930"));
        assertEquals(List.of(new CurrentQuote("005930", "200", "0")), provider.getCurrentPrices(List.of("005930")));
        server.verify();
    }

    @Test
    public void businessErrorDoesNotReturnPriceOrExposeRemoteMessage() {
        expect(scope, "005930", "synthetic-token").andRespond(withSuccess(
                "{\"cur_prc\":\"100\",\"return_code\":10,\"return_msg\":\"synthetic-sensitive\"}",
                MediaType.APPLICATION_JSON));
        assertSanitizedFailure();
        server.verify();
    }

    @Test
    public void emptyMalformedAndNonObjectBodiesFailWithoutRawPayload() {
        for (String body : List.of("", "null", "[]", "synthetic-sensitive", "{\"cur_prc\":{\"token\":\"synthetic-sensitive\"}}")) {
            server.reset();
            expect(scope, "005930", "synthetic-token").andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertSanitizedFailure();
            server.verify();
        }
    }

    @Test
    public void authRateLimitAndServerHttpErrorsHaveNoRetryOrTokenInvalidation() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.SERVICE_UNAVAILABLE)) {
            server.reset();
            expect(scope, "005930", "synthetic-token").andRespond(withStatus(status)
                    .body("synthetic-sensitive").contentType(MediaType.APPLICATION_JSON));
            assertSanitizedFailure();
            assertEquals("synthetic-token", repository.findAccessToken(scope, 600).orElseThrow());
            assertTrue(locks.keys.isEmpty());
            server.verify();
        }
    }

    @Test
    public void transportFailureIsSanitized() {
        expect(scope, "005930", "synthetic-token").andRespond(request -> {
            throw new IOException("synthetic-sensitive");
        });
        assertSanitizedFailure();
        server.verify();
    }

    @Test
    public void tokenFailureStopsBeforePriceHttp() {
        redis.failReads = true;
        assertSanitizedFailure();
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void listContinuesAfterTokenBackendFailureWithoutLeakingIt() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        KiwoomTokenService tokens = new KiwoomTokenService(scope, 600, rest, repository, locks.client(), clock) {
            @Override public String getAccessToken() {
                if (calls.incrementAndGet() == 1) throw new UnsupportedOperationException("synthetic-sensitive");
                return super.getAccessToken();
            }
        };
        quote("000660", "200", "0");
        CurrentPriceProvider adapter = new KiwoomCurrentPriceProvider(tokens, rest);
        assertEquals(List.of(new CurrentQuote("000660", "200", "0")),
                adapter.getCurrentPrices(List.of("005930", "000660")));
        assertEquals(2, calls.get());
        server.verify();
    }

    @Test
    public void actualRestTemplateDebugLogsDoNotExposeBearerOrRemoteMessage() {
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(RestTemplate.class);
        org.apache.log4j.Level previous = logger.getLevel();
        java.io.StringWriter output = new java.io.StringWriter();
        org.apache.log4j.WriterAppender appender = new org.apache.log4j.WriterAppender(
                new org.apache.log4j.PatternLayout("%m%n"), output);
        logger.addAppender(appender);
        logger.setLevel(org.apache.log4j.Level.DEBUG);
        try {
            expect(scope, "005930", "synthetic-token").andRespond(withSuccess(
                    "{\"cur_prc\":\"100\",\"return_msg\":\"synthetic-sensitive\"}", MediaType.APPLICATION_JSON));
            assertEquals(Integer.valueOf(100), provider.getCurrentPrice("005930"));
            assertTrue(output.toString().contains("Writing ["));
            for (String secret : List.of("synthetic-token", "synthetic-key", "synthetic-secret", "synthetic-sensitive")) {
                assertFalse(output.toString().contains(secret));
            }
            server.verify();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.close();
        }
    }

    @Test
    public void firstMissIssuesTokenThenUsesSameEnvironmentForPrice() {
        KiwoomAccessTokenScope real = scope("real");
        server.expect(requestTo(real.getBaseUrl() + "/oauth2/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\":\"real-token\",\"token_type\":\"bearer\","
                        + "\"expires_dt\":\"20261024090000\",\"return_code\":0}", MediaType.APPLICATION_JSON));
        expect(real, "005930", "real-token").andRespond(withSuccess("{\"cur_prc\":\"100\"}", MediaType.APPLICATION_JSON));
        assertEquals(Integer.valueOf(100), provider(real).getCurrentPrice("005930"));
        assertEquals(List.of(real.getLockKey()), locks.keys);
        assertEquals("synthetic-token", repository.findAccessToken(scope, 600).orElseThrow());
        server.verify();
    }

    @Test
    public void realAndVirtualReuseOnlyTheirOwnTokensInBothOrders() {
        KiwoomAccessTokenScope real = scope("real");
        cacheToken(real, "real-token");
        for (boolean realFirst : List.of(true, false)) {
            server.reset();
            KiwoomAccessTokenScope first = realFirst ? real : scope;
            KiwoomAccessTokenScope second = realFirst ? scope : real;
            expect(first, "005930", realFirst ? "real-token" : "synthetic-token")
                    .andRespond(withSuccess("{\"cur_prc\":\"100\"}", MediaType.APPLICATION_JSON));
            expect(second, "005930", realFirst ? "synthetic-token" : "real-token")
                    .andRespond(withSuccess("{\"cur_prc\":\"200\"}", MediaType.APPLICATION_JSON));
            assertEquals(Integer.valueOf(100), provider(first).getCurrentPrice("005930"));
            assertEquals(Integer.valueOf(200), provider(second).getCurrentPrice("005930"));
            server.verify();
        }
        assertTrue(locks.keys.isEmpty());
    }

    @Test
    public void repeatedPriceRequestsCallHttpWithoutCachingOrExtendingTokenTtl() {
        Long expiry = redis.expirations.get(scope.getTokenKey());
        quote("005930", "100", "0");
        quote("005930", "200", "0");
        assertEquals(Integer.valueOf(100), provider.getCurrentPrice("005930"));
        clock.advanceSeconds(1);
        assertEquals(Integer.valueOf(200), provider.getCurrentPrice("005930"));
        assertEquals(expiry, redis.expirations.get(scope.getTokenKey()));
        assertEquals(1, redis.writes.get());
        assertEquals(1, redis.values.size());
        server.verify();
    }

    private void assertSanitizedFailure() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.getCurrentPrice("005930"));
        assertFalse(error.getMessage().contains("synthetic-sensitive"));
        assertNull(error.getCause());
    }

    private KiwoomAccessTokenScope scope(String mode) {
        return new KiwoomAccessTokenScope(mode, "synthetic-key", "synthetic-secret");
    }

    private CurrentPriceProvider provider(KiwoomAccessTokenScope tokenScope) {
        return new KiwoomCurrentPriceProvider(new KiwoomTokenService(tokenScope, 600, rest, repository,
                locks.client(), clock), rest);
    }

    private void cacheToken(KiwoomAccessTokenScope tokenScope, String token) {
        repository.save(tokenScope, token, clock.instant().plusSeconds(86400), 600);
    }

    private ResponseActions expect(KiwoomAccessTokenScope tokenScope, String code, String token) {
        return server.expect(requestTo(tokenScope.getBaseUrl() + "/api/dostk/stkinfo"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-id", "ka10001"))
                .andExpect(header("Authorization", "Bearer " + token))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(request -> {
                    JsonNode body = new ObjectMapper().readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertEquals(code, body.path("stk_cd").asText());
                    assertEquals(1, body.size());
                    assertFalse(request.getHeaders().containsKey("appkey"));
                    assertFalse(request.getHeaders().containsKey("secretkey"));
                    assertFalse(request.getHeaders().containsKey("next-key"));
                });
    }

    private void quote(String code, String price, String rate) {
        com.fasterxml.jackson.databind.node.ObjectNode body = new ObjectMapper().createObjectNode();
        body.put("stk_cd", code);
        body.put("cur_prc", price);
        body.put("flu_rt", rate);
        body.put("return_code", 0);
        expect(scope, code, "synthetic-token").andRespond(withSuccess(body.toString(), MediaType.APPLICATION_JSON));
    }
}
