package com.antmillion.kiwoom;

import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenRequest;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenResponse;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 합성 자격증명·시각·Redis·잠금·HTTP만 사용하며 개발 설정이나 외부 연결을 생성하지 않습니다. */
public class KiwoomTokenServiceTest {
    private MutableClock clock;
    private MemoryRedis redis;
    private LockClient locks;
    private RestTemplate rest;
    private MockRestServiceServer server;
    private KiwoomAccessTokenRedisRepository repository;

    @Before
    public void setUp() {
        clock = new MutableClock();
        redis = new MemoryRedis(clock);
        locks = new LockClient();
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        repository = new KiwoomAccessTokenRedisRepository(redis, clock);
    }

    @Test
    public void realThenVirtualHaveIndependentTokensAndLocks() {
        assertSeparated(scope("real", "key-a", "secret-a"), scope("virtual", "key-a", "secret-a"));
    }

    @Test
    public void virtualThenRealHaveIndependentTokensAndLocks() {
        assertSeparated(scope("virtual", "key-a", "secret-a"), scope("real", "key-a", "secret-a"));
    }

    @Test
    public void differentCredentialsHaveIndependentTokensAndLocks() {
        KiwoomAccessTokenScope first = scope("real", "key-a", "secret-a");
        KiwoomAccessTokenScope second = scope("real", "key-b", "secret-a");
        KiwoomAccessTokenScope third = scope("real", "key-a", "secret-b");
        expect(first, response("first", "20261024090000"));
        expect(second, response("second", "20261024090000"));
        expect(third, response("third", "20261024090000"));
        assertEquals("first", service(first).getAccessToken());
        assertEquals("second", service(second).getAccessToken());
        assertEquals("third", service(third).getAccessToken());
        assertEquals(3, redis.values.size());
        assertEquals(3, locks.keys.stream().distinct().count());
        server.verify();
    }

    @Test
    public void scopeKeysHaveUnambiguousBoundariesWithoutPlaintextCredentials() {
        KiwoomAccessTokenScope first = scope("real", "synthetic|key", "secret");
        KiwoomAccessTokenScope second = scope("real", "synthetic", "key|secret");
        assertNotEquals(first.getTokenKey(), second.getTokenKey());
        assertEquals(first.getTokenKey(), scope("REAL", "synthetic|key", "secret").getTokenKey());
        assertTrue(first.getTokenKey().matches("kiwoom:access_token:v1:[0-9a-f]{64}"));
        assertTrue(first.getLockKey().matches("kiwoom:token:lock:v1:[0-9a-f]{64}"));
        assertFalse(first.getTokenKey().contains("synthetic"));
        assertFalse(first.getLockKey().contains("secret"));
        assertFalse(first.toString().contains("synthetic"));
    }

    @Test
    public void requestAndResponseToStringDoNotExposeSecrets() {
        KiwoomAccessTokenRequest request = new KiwoomAccessTokenRequest("synthetic-key", "synthetic-secret");
        KiwoomAccessTokenResponse response = new KiwoomAccessTokenResponse();
        response.setToken("synthetic-token");
        assertFalse(request.toString().contains("synthetic-key"));
        assertFalse(request.toString().contains("synthetic-secret"));
        assertFalse(response.toString().contains("synthetic-token"));
    }

    @Test
    public void actualRestTemplateDebugLogsDoNotExposeCredentialsOrToken() {
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(RestTemplate.class);
        org.apache.log4j.Level previous = logger.getLevel();
        java.io.StringWriter output = new java.io.StringWriter();
        org.apache.log4j.WriterAppender appender = new org.apache.log4j.WriterAppender(
                new org.apache.log4j.PatternLayout("%m%n"), output);
        logger.addAppender(appender);
        logger.setLevel(org.apache.log4j.Level.DEBUG);
        try {
            KiwoomAccessTokenScope scope = scope("virtual", "debug-private-key", "debug-private-secret");
            expect(scope, response("debug-private-token", "20261024090000"));
            assertEquals("debug-private-token", service(scope).getAccessToken());
            assertTrue(output.toString().contains("Writing ["));
            assertFalse(output.toString().contains("debug-private-key"));
            assertFalse(output.toString().contains("debug-private-secret"));
            assertFalse(output.toString().contains("debug-private-token"));
            server.verify();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.close();
        }
    }

    @Test
    public void validTokenIsReusedWithoutHttpLockOrTtlExtension() {
        KiwoomAccessTokenScope scope = scope();
        expect(scope, response("shared", "20261024090000"));
        assertEquals("shared", service(scope).getAccessToken());
        long expires = redis.expirations.get(scope.getTokenKey());
        assertEquals(Long.valueOf(85800000L), redis.ttls.get(scope.getTokenKey()));
        clock.advanceSeconds(100);
        assertEquals("shared", service(scope).getAccessToken());
        assertEquals(Long.valueOf(expires), redis.expirations.get(scope.getTokenKey()));
        assertEquals(1, locks.keys.size());
        server.verify();
    }

    @Test
    public void refreshesAtExactBufferBoundaryUsingKoreanTimeOnUtcJvmClock() {
        KiwoomAccessTokenScope scope = scope();
        expect(scope, response("first", "20261023100000"));
        expect(scope, response("refreshed", "20261024090000"));
        assertEquals("first", service(scope).getAccessToken());
        clock.advanceSeconds(2999);
        assertEquals("first", service(scope).getAccessToken());
        clock.advanceSeconds(1);
        assertEquals("refreshed", service(scope).getAccessToken());
        assertEquals(2, locks.unlocks.get());
        server.verify();
    }

    @Test
    public void stricterBufferChecksStoredAbsoluteExpiryEvenBeforeRedisTtl() {
        KiwoomAccessTokenScope scope = scope();
        repository.save(scope, "short-lived", clock.instant().plusSeconds(300), 0);
        expect(scope, response("new", "20261024090000"));
        assertEquals("new", service(scope).getAccessToken());
        server.verify();
    }

    @Test
    public void legacyKisAndOtherScopeValuesAreNotReadOrChanged() {
        redis.values.put("kis:access_token", "kis-token");
        redis.values.put("kiwoom:access_token", "legacy-token");
        KiwoomAccessTokenScope other = scope("real", "other-key", "secret-a");
        repository.save(other, "other-token", clock.instant().plusSeconds(86400), 600);
        expect(scope(), response("new", "20261024090000"));
        assertEquals("new", service(scope()).getAccessToken());
        assertEquals("kis-token", redis.values.get("kis:access_token"));
        assertEquals("legacy-token", redis.values.get("kiwoom:access_token"));
        assertEquals("other-token", repository.findAccessToken(other, 600).orElseThrow());
        assertFalse(redis.reads.contains("kis:access_token"));
        assertFalse(redis.reads.contains("kiwoom:access_token"));
        server.verify();
    }

    @Test
    public void corruptAndExpiredCacheAreReplaced() {
        for (String value : List.of("{broken", "null", "{}", "{\"token\":\"old\",\"expires_at\":0}")) {
            server.reset();
            redis.values.put(scope().getTokenKey(), value);
            expect(scope(), response("new", "20261024090000"));
            assertEquals("new", service(scope()).getAccessToken());
            server.verify();
        }
    }

    @Test
    public void cachedTokenPublishedWhileWaitingIsRecheckedUnderLock() {
        locks.onAcquire = () -> repository.save(scope(), "worker-token", clock.instant().plusSeconds(86400), 600);
        assertEquals("worker-token", service(scope()).getAccessToken());
        assertEquals(1, locks.unlocks.get());
        server.verify();
    }

    @Test
    public void failedLockCanReuseSameScopeTokenWithoutUnlockingAnotherOwner() {
        locks.deny = true;
        locks.onAcquire = () -> repository.save(scope(), "worker-token", clock.instant().plusSeconds(86400), 600);
        assertEquals("worker-token", service(scope()).getAccessToken());
        assertEquals(0, locks.unlocks.get());
        server.verify();
    }

    @Test
    public void failedLockWithoutTokenDoesNotIssueHttp() {
        locks.deny = true;
        assertThrows(IllegalStateException.class, () -> service(scope()).getAccessToken());
        assertTrue(redis.values.isEmpty());
        assertEquals(0, locks.unlocks.get());
        server.verify();
    }

    @Test
    public void interruptedLockWaitRestoresFlagWithoutHttpOrUnlock() {
        locks.interrupt = true;
        try {
            assertThrows(IllegalStateException.class, () -> service(scope()).getAccessToken());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, locks.unlocks.get());
            server.verify();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void concurrentFirstRequestsIssueOnlyOnceAcrossServiceInstances() throws Exception {
        int workers = 8;
        redis.initialMisses = new CountDownLatch(workers);
        expect(scope(), response("one-token", "20261024090000"), () -> {
            try {
                assertTrue(redis.initialMisses.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                KiwoomTokenService service = service(scope());
                results.add(executor.submit(service::getAccessToken));
            }
            for (Future<String> result : results) assertEquals("one-token", result.get(10, TimeUnit.SECONDS));
            assertEquals(1, redis.writes.get());
            assertEquals(1, locks.keys.stream().distinct().count());
            assertEquals(workers, locks.unlocks.get());
            server.verify();
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void httpFailureIsSanitizedAndReleasesLockWithoutSaving() {
        server.expect(requestTo(scope().getBaseUrl() + "/oauth2/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("synthetic-sensitive-response"));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service(scope()).getAccessToken());
        assertFalse(error.toString().contains("synthetic-sensitive-response"));
        assertNull(error.getCause());
        assertFailedWithoutSaving();
    }

    @Test
    public void storageFailureReleasesLockAndDoesNotReturnToken() {
        redis.failWrites = true;
        expect(scope(), response("new", "20261024090000"));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service(scope()).getAccessToken());
        assertFalse(error.toString().contains("synthetic-sensitive-storage"));
        assertNull(error.getCause());
        assertFailedWithoutSaving();
    }

    @Test
    public void storageReadFailureIsSanitizedWithoutHttpOrLock() {
        redis.failReads = true;
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service(scope()).getAccessToken());
        assertFalse(error.toString().contains("synthetic-sensitive-storage"));
        assertNull(error.getCause());
        assertTrue(locks.keys.isEmpty());
        server.verify();
    }

    @Test
    public void lostLockDoesNotSaveOrReturnIssuedToken() {
        expect(scope(), response("new", "20261024090000"), () -> locks.lost = true);
        assertThrows(IllegalStateException.class, () -> service(scope()).getAccessToken());
        assertTrue(redis.values.isEmpty());
        assertEquals(0, locks.unlocks.get());
        // 잠금 소유권 상실 대역이 남긴 로컬 ReentrantLock만 정리합니다.
        locks.actual.get(scope().getLockKey()).unlock();
        server.verify();
    }

    @Test
    public void businessFailureAndMalformedResponseDoNotLeakRemoteValues() {
        for (String value : List.of(
                "{\"return_code\":1,\"return_msg\":\"sensitive\",\"token\":\"sensitive\"}",
                "{\"token\":\"sensitive\",\"token_type\":\"bearer\",\"expires_dt\":\"sensitive\"}",
                "{\"token\":\"sensitive\",\"token_type\":\"bearer\",\"expires_dt\":\"20260230090000\"}",
                "{\"token\":\"sensitive\",\"token_type\":\"invalid\",\"expires_dt\":\"20261024090000\"}",
                "{\"token\":\"\",\"token_type\":\"bearer\",\"expires_dt\":\"20261024090000\"}",
                "{broken-sensitive")) {
            server.reset();
            expect(scope(), value);
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> service(scope()).getAccessToken());
            assertFalse(error.toString().contains("sensitive"));
            assertNull(error.getCause());
            assertTrue(redis.values.isEmpty());
            server.verify();
        }
        assertEquals(6, locks.unlocks.get());
    }

    @Test
    public void missingBodyOrRequiredFieldsAreRejected() {
        for (String value : List.of("", "{}", "null", "{\"token\":\"new\",\"token_type\":\"bearer\"}")) {
            server.reset();
            expect(scope(), value);
            assertThrows(IllegalStateException.class, () -> service(scope()).getAccessToken());
            assertTrue(redis.values.isEmpty());
            server.verify();
        }
        assertEquals(4, locks.unlocks.get());
    }

    @Test
    public void expiredAndWithinBufferTokensAreNotSavedOrReturned() {
        for (String expiry : List.of("20261023085959", "20261023090000", "20261023091000")) {
            server.reset();
            expect(scope(), response("too-short", expiry));
            assertThrows(IllegalStateException.class, () -> service(scope()).getAccessToken());
            assertTrue(redis.values.isEmpty());
            server.verify();
        }
    }

    @Test
    public void responseTimeIsDeductedFromTtl() {
        expect(scope(), response("new", "20261023100000"), () -> clock.advanceSeconds(90));
        assertEquals("new", service(scope()).getAccessToken());
        assertEquals(Long.valueOf(2910000L), redis.ttls.get(scope().getTokenKey()));
        server.verify();
    }

    @Test
    public void absentOptionalReturnCodeAndMixedCaseBearerAreAccepted() {
        expect(scope(), "{\"token\":\"new\",\"token_type\":\"Bearer\",\"expires_dt\":\"20261024090000\"}");
        assertEquals("new", service(scope()).getAccessToken());
        server.verify();
    }

    @Test
    public void cachedJsonStringRoundTripsThroughExistingRedisSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.activateDefaultTyping(mapper.getPolymorphicTypeValidator(), ObjectMapper.DefaultTyping.NON_FINAL);
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);
        repository.save(scope(), "cached", clock.instant().plusSeconds(86400), 600);
        Object encoded = redis.values.get(scope().getTokenKey());
        redis.values.put(scope().getTokenKey(), serializer.deserialize(serializer.serialize(encoded)));
        assertEquals("cached", repository.findAccessToken(scope(), 600).orElseThrow());
    }

    private void assertSeparated(KiwoomAccessTokenScope first, KiwoomAccessTokenScope second) {
        expect(first, response("first", "20261024090000"));
        expect(second, response("second", "20261024090000"));
        assertEquals("first", service(first).getAccessToken());
        assertEquals("second", service(second).getAccessToken());
        assertEquals("first", service(first).getAccessToken());
        assertNotEquals(first.getTokenKey(), second.getTokenKey());
        assertNotEquals(first.getLockKey(), second.getLockKey());
        assertEquals(2, locks.unlocks.get());
        server.verify();
    }

    private void assertFailedWithoutSaving() {
        assertEquals(1, locks.unlocks.get());
        assertTrue(redis.values.isEmpty());
        server.verify();
    }

    private static String response(String token, String expiry) {
        return "{\"token\":\"" + token + "\",\"token_type\":\"bearer\",\"expires_dt\":\""
                + expiry + "\",\"return_code\":0,\"return_msg\":\"ok\"}";
    }

    private static KiwoomAccessTokenScope scope() { return scope("virtual", "key-a", "secret-a"); }
    private static KiwoomAccessTokenScope scope(String mode, String key, String secret) {
        return new KiwoomAccessTokenScope(mode, key, secret);
    }

    private KiwoomTokenService service(KiwoomAccessTokenScope scope) {
        return new KiwoomTokenService(scope, 600, rest, repository, locks.client(), clock);
    }

    private void expect(KiwoomAccessTokenScope scope, String response) { expect(scope, response, () -> { }); }

    private void expect(KiwoomAccessTokenScope scope, String response, Runnable duringResponse) {
        server.expect(requestTo(scope.getBaseUrl() + "/oauth2/token")).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    JsonNode body = new ObjectMapper().readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertEquals("client_credentials", body.path("grant_type").asText());
                    assertEquals(scope.getAppKey(), body.path("appkey").asText());
                    assertEquals(scope.getAppSecret(), body.path("secretkey").asText());
                    assertEquals(3, body.size());
                    assertFalse(request.getHeaders().containsKey("Authorization"));
                }).andRespond(request -> {
                    duringResponse.run();
                    return withSuccess(response, MediaType.APPLICATION_JSON).createResponse(request);
                });
    }

    static class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-23T00:00:00Z");
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    static class MemoryRedis extends RedisTemplate<String, Object> {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        final Map<String, Long> ttls = new ConcurrentHashMap<>();
        final Map<String, Long> expirations = new ConcurrentHashMap<>();
        final List<String> reads = new CopyOnWriteArrayList<>();
        final AtomicInteger writes = new AtomicInteger();
        final java.util.Set<Thread> missThreads = ConcurrentHashMap.newKeySet();
        final Clock clock;
        volatile CountDownLatch initialMisses;
        boolean failWrites;
        boolean failReads;
        MemoryRedis(Clock clock) { this.clock = clock; }

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, Object> opsForValue() {
            return (ValueOperations<String, Object>) Proxy.newProxyInstance(ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class}, (proxy, method, args) -> {
                        String key = (String) args[0];
                        if ("get".equals(method.getName())) {
                            if (failReads) throw new IllegalStateException("synthetic-sensitive-storage");
                            reads.add(key);
                            Long expiry = expirations.get(key);
                            if (expiry != null && expiry <= clock.millis()) values.remove(key);
                            Object value = values.get(key);
                            if (value == null && initialMisses != null && missThreads.add(Thread.currentThread())) {
                                initialMisses.countDown();
                            }
                            return value;
                        }
                        if ("set".equals(method.getName()) && args.length == 4) {
                            if (failWrites) throw new IllegalStateException("synthetic-sensitive-storage");
                            long ttl = ((TimeUnit) args[3]).toMillis((Long) args[2]);
                            values.put(key, args[1]);
                            ttls.put(key, ttl);
                            expirations.put(key, clock.millis() + ttl);
                            writes.incrementAndGet();
                            return null;
                        }
                        throw new AssertionError("예상하지 않은 Redis 호출: " + method.getName());
                    });
        }
    }

    static class LockClient {
        final Map<String, ReentrantLock> actual = new ConcurrentHashMap<>();
        final List<String> keys = new CopyOnWriteArrayList<>();
        final AtomicInteger unlocks = new AtomicInteger();
        boolean deny;
        boolean interrupt;
        boolean lost;
        Runnable onAcquire = () -> { };

        RedissonClient client() {
            return (RedissonClient) Proxy.newProxyInstance(RedissonClient.class.getClassLoader(),
                    new Class<?>[]{RedissonClient.class}, (proxy, method, args) -> {
                        if (!"getLock".equals(method.getName())) throw new AssertionError(method.getName());
                        String key = (String) args[0];
                        keys.add(key);
                        ReentrantLock actualLock = actual.computeIfAbsent(key, ignored -> new ReentrantLock());
                        return Proxy.newProxyInstance(RLock.class.getClassLoader(), new Class<?>[]{RLock.class},
                                (lock, operation, arguments) -> {
                                    if ("tryLock".equals(operation.getName())) {
                                        assertArrayEquals(new Object[]{5L, TimeUnit.SECONDS}, arguments);
                                        if (interrupt) throw new InterruptedException("합성 대기 중단");
                                        boolean acquired = !deny && actualLock.tryLock(5, TimeUnit.SECONDS);
                                        onAcquire.run();
                                        return acquired;
                                    }
                                    if ("isHeldByCurrentThread".equals(operation.getName())) {
                                        return !lost && actualLock.isHeldByCurrentThread();
                                    }
                                    if ("unlock".equals(operation.getName())) {
                                        actualLock.unlock();
                                        unlocks.incrementAndGet();
                                        return null;
                                    }
                                    throw new AssertionError(operation.getName());
                                });
                    });
        }
    }
}
