package com.antmillion.kis;

import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.dto.KisAccessTokenScope;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpServerErrorException;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 설정·DB·Redis·증권사 없이 토큰 HTTP와 잠금 대역만 사용합니다. */
public class KisAccessTokenIsolationTest {
    private MemoryRedis redis;
    private LockClient locks;
    private RestTemplate rest;
    private MockRestServiceServer server;

    @Before
    public void setUp() {
        redis = new MemoryRedis();
        locks = new LockClient();
        rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
    }

    @Test
    public void realThenVirtualDoNotReuseTokenOrLock() {
        assertSeparated(config("real", "key-a", "secret-a"), config("virtual", "key-a", "secret-a"));
    }

    @Test
    public void virtualThenRealDoNotReuseTokenOrLock() {
        assertSeparated(config("virtual", "key-a", "secret-a"), config("real", "key-a", "secret-a"));
    }

    @Test
    public void differentAppKeysDoNotReuseTokenOrLock() {
        assertSeparated(config("real", "key-a", "secret-a"), config("real", "key-b", "secret-a"));
    }

    @Test
    public void changedSecretDoesNotReuseTokenOrLock() {
        assertSeparated(config("real", "key-a", "secret-a"), config("real", "key-a", "secret-b"));
    }

    @Test
    public void actualEndpointDefinesScopeInsteadOfModeLabel() {
        KisConfig first = config("real", "key-a", "secret-a");
        KisConfig second = config("REAL", "key-a", "secret-a");
        expectToken(first, "shared-token");
        assertEquals("shared-token", service(first).getKisAccessToken());
        assertEquals("shared-token", service(second).getKisAccessToken());
        assertEquals(1, locks.keys.size());
        server.verify();
    }

    @Test
    public void sameModeWithDifferentActualEndpointsDoesNotShareTokenOrLock() {
        KisConfig first = config("real", "key-a", "secret-a");
        KisConfig second = new KisConfig() {
            @Override public String getBaseUrl() { return "https://other.kis.test"; }
        };
        second.mode = first.mode;
        second.appKey = first.appKey;
        second.appSecret = first.appSecret;
        assertSeparated(first, second);
    }

    @Test
    public void scopedDeletionKeepsOtherCredentialsEnvironmentAndLegacyKeys() {
        KisAccessTokenRedisRepository repository = new KisAccessTokenRedisRepository(redis);
        KisAccessTokenScope first = scope(config("real", "key-a", "secret-a"));
        KisAccessTokenScope second = scope(config("real", "key-b", "secret-a"));
        KisAccessTokenScope virtual = scope(config("virtual", "key-a", "secret-a"));
        redis.values.put("kis:access_token", "legacy-token");
        redis.values.put("kiwoom:access_token", "other-provider-token");
        repository.save(first, "token-a", 123);
        repository.save(second, "token-b", 456);
        repository.save(virtual, "virtual-token", 789);
        assertEquals(Long.valueOf(123), redis.ttls.get(first.getTokenKey()));
        repository.deleteAccessToken(first);
        assertFalse(repository.findAccessToken(first).isPresent());
        assertEquals("token-b", repository.findAccessToken(second).orElseThrow());
        assertEquals("virtual-token", repository.findAccessToken(virtual).orElseThrow());
        assertEquals("legacy-token", redis.values.get("kis:access_token"));
        assertEquals("other-provider-token", redis.values.get("kiwoom:access_token"));
        assertEquals(List.of(first.getTokenKey()), redis.deletes);
    }

    @Test
    public void legacyAndOtherProviderTokensAreNeitherReadNorChanged() {
        KisConfig config = config("real", "key-a", "secret-a");
        redis.values.put("kis:access_token", "legacy-token");
        redis.values.put("kiwoom:access_token", "other-provider-token");
        expectToken(config, "new-token");
        assertEquals("new-token", service(config).getKisAccessToken());
        assertFalse(redis.reads.contains("kis:access_token"));
        assertFalse(redis.reads.contains("kiwoom:access_token"));
        assertEquals("legacy-token", redis.values.get("kis:access_token"));
        assertEquals("other-provider-token", redis.values.get("kiwoom:access_token"));
        assertEquals(Long.valueOf(86400), redis.ttls.get(scope(config).getTokenKey()));
        assertEquals(List.of(scope(config).getLockKey()), locks.keys);
        assertTrue(redis.deletes.isEmpty());
        server.verify();
    }

    @Test
    public void tokenPublishedWhileWaitingIsRecheckedUnderTheSameLock() {
        KisConfig config = config("real", "key-a", "secret-a");
        KisAccessTokenScope scope = scope(config);
        locks.onAcquire = () -> redis.values.put(scope.getTokenKey(), "other-worker-token");
        assertEquals("other-worker-token", service(config).getKisAccessToken());
        assertEquals(List.of(scope.getTokenKey(), scope.getTokenKey()), redis.reads);
        assertEquals(List.of(scope.getLockKey()), locks.keys);
        assertEquals(1, locks.unlocks);
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void lockMissUsesOnlyTheSameScopeWithoutUnlockingAnotherOwner() {
        KisConfig config = config("real", "key-a", "secret-a");
        KisAccessTokenScope scope = scope(config);
        locks.acquire = false;
        locks.onAcquire = () -> redis.values.put(scope.getTokenKey(), "other-worker-token");
        assertEquals("other-worker-token", service(config).getKisAccessToken());
        assertEquals(List.of(scope.getTokenKey(), scope.getTokenKey()), redis.reads);
        assertEquals(0, locks.unlocks);
        server.verify();
    }

    @Test
    public void lockMissDoesNotFallBackToDifferentScopeOrLegacyToken() {
        KisConfig config = config("real", "key-a", "secret-a");
        redis.values.put(scope(config("virtual", "key-a", "secret-a")).getTokenKey(), "wrong-token");
        redis.values.put("kis:access_token", "legacy-token");
        locks.acquire = false;
        assertThrows(RuntimeException.class, () -> service(config).getKisAccessToken());
        assertEquals(List.of(scope(config).getTokenKey(), scope(config).getTokenKey()), redis.reads);
        assertEquals(0, locks.unlocks);
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void issuanceFailureReleasesOwnedLockWithoutSavingToken() {
        KisConfig config = config("real", "key-a", "secret-a");
        server.expect(requestTo(config.getBaseUrl() + "/oauth2/tokenP"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(HttpServerErrorException.class, () -> service(config).getKisAccessToken());
        assertEquals(1, locks.unlocks);
        assertTrue(redis.values.isEmpty());
        assertTrue(redis.ttls.isEmpty());
        server.verify();
    }

    @Test
    public void storageFailureStillReleasesOwnedLock() {
        KisConfig config = config("real", "key-a", "secret-a");
        expectToken(config, "new-token");
        redis.failWrites = true;
        assertThrows(IllegalStateException.class, () -> service(config).getKisAccessToken());
        assertEquals(1, locks.unlocks);
        assertTrue(redis.values.isEmpty());
        server.verify();
    }

    @Test
    public void interruptedLockWaitRestoresInterruptFlagWithoutIssuingToken() {
        locks.interrupt = true;
        try {
            assertThrows(RuntimeException.class,
                    () -> service(config("real", "key-a", "secret-a")).getKisAccessToken());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, locks.unlocks);
            assertTrue(redis.values.isEmpty());
            server.verify();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void configurationChangesWhileWaitingDoNotChangeIssuanceOrStorageScope() {
        KisConfig config = config("real", "key-a", "secret-a");
        KisAccessTokenScope initial = scope(config);
        expectToken(config, "original-scope-token");
        locks.onAcquire = () -> {
            config.mode = "virtual";
            config.appKey = "key-b";
            config.appSecret = "secret-b";
        };
        assertEquals("original-scope-token", service(config).getKisAccessToken());
        assertEquals("original-scope-token", redis.values.get(initial.getTokenKey()));
        assertFalse(redis.values.containsKey(scope(config).getTokenKey()));
        assertEquals(List.of(initial.getTokenKey(), initial.getTokenKey()), redis.reads);
        assertEquals(List.of(initial.getLockKey()), locks.keys);
        assertEquals(1, locks.unlocks);
        server.verify();
    }

    @Test
    public void identityDoesNotExposeCredentialsAndSeparatesFieldBoundaries() {
        KisAccessTokenScope first = new KisAccessTokenScope("https://kis.test", "key:part", "secret");
        KisAccessTokenScope second = new KisAccessTokenScope("https://kis.test", "key", "part:secret");
        KisAccessTokenScope repeated = new KisAccessTokenScope("https://kis.test", "key:part", "secret");
        assertNotEquals(first.getTokenKey(), second.getTokenKey());
        assertNotEquals(first.getLockKey(), second.getLockKey());
        assertEquals(first.getTokenKey(), repeated.getTokenKey());
        assertEquals(first.getLockKey(), repeated.getLockKey());
        assertTrue(first.getTokenKey().matches("kis:access_token:v2:[0-9a-f]{64}"));
        assertTrue(first.getLockKey().matches("kis:token:lock:v2:[0-9a-f]{64}"));
        assertFalse(first.toString().contains("key:part"));
        assertFalse(first.toString().contains("secret"));
    }

    private KisAccessTokenScope scope(KisConfig config) {
        return new KisAccessTokenScope(config.getBaseUrl(), config.appKey, config.appSecret);
    }

    private void assertSeparated(KisConfig first, KisConfig second) {
        expectToken(first, "token-a");
        expectToken(second, "token-b");
        KisApiService serviceA = service(first);
        KisApiService serviceB = service(second);
        assertEquals("token-a", serviceA.getKisAccessToken());
        assertEquals("token-b", serviceB.getKisAccessToken());
        assertEquals("token-a", serviceA.getKisAccessToken());
        assertEquals("token-b", serviceB.getKisAccessToken());
        assertEquals(2, locks.keys.size());
        assertNotEquals(locks.keys.get(0), locks.keys.get(1));
        assertEquals(2, locks.unlocks);
        assertEquals(2, redis.values.size());
        server.verify();
    }

    private KisConfig config(String mode, String key, String secret) {
        KisConfig config = new KisConfig();
        config.mode = mode;
        config.appKey = key;
        config.appSecret = secret;
        return config;
    }

    private KisApiService service(KisConfig config) {
        return new KisApiService(config, null, rest, new KisAccessTokenRedisRepository(redis),
                null, null, null, null, locks.client(), null);
    }

    private void expectToken(KisConfig config, String token) {
        String appKey = config.appKey;
        String appSecret = config.appSecret;
        server.expect(requestTo(config.getBaseUrl() + "/oauth2/tokenP"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode body = new ObjectMapper().readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertEquals("client_credentials", body.get("grant_type").asText());
                    assertEquals(appKey, body.get("appkey").asText());
                    assertEquals(appSecret, body.get("appsecret").asText());
                })
                .andRespond(withSuccess("{\"access_token\":\"" + token
                        + "\",\"expires_in\":86400}", MediaType.APPLICATION_JSON));
    }

    private static class MemoryRedis extends RedisTemplate<String, Object> {
        final Map<String, Object> values = new HashMap<>();
        final Map<String, Long> ttls = new HashMap<>();
        final List<String> reads = new ArrayList<>();
        final List<String> deletes = new ArrayList<>();
        boolean failWrites;

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
                            if (failWrites) throw new IllegalStateException("합성 Redis 저장 실패");
                            values.put((String) args[0], args[1]);
                            ttls.put((String) args[0], ((TimeUnit) args[3]).toSeconds((Long) args[2]));
                            return null;
                        }
                        throw new AssertionError("예상하지 않은 Redis 호출: " + method.getName());
                    });
        }

        @Override
        public Boolean delete(String key) {
            deletes.add(key);
            ttls.remove(key);
            return values.remove(key) != null;
        }
    }

    private static class LockClient {
        final List<String> keys = new ArrayList<>();
        int unlocks;
        boolean acquire = true;
        boolean interrupt;
        boolean held;
        Runnable onAcquire = () -> { };

        RedissonClient client() {
            return (RedissonClient) Proxy.newProxyInstance(RedissonClient.class.getClassLoader(),
                    new Class<?>[]{RedissonClient.class}, (proxy, method, args) -> {
                        if ("getLock".equals(method.getName())) {
                            keys.add((String) args[0]);
                            return Proxy.newProxyInstance(RLock.class.getClassLoader(), new Class<?>[]{RLock.class},
                                    (lock, operation, arguments) -> {
                                        if ("tryLock".equals(operation.getName())) {
                                            assertArrayEquals(new Object[]{5L, 10L, TimeUnit.SECONDS}, arguments);
                                            if (interrupt) throw new InterruptedException("합성 잠금 대기 중단");
                                            held = acquire;
                                            onAcquire.run();
                                            return acquire;
                                        }
                                        if ("isHeldByCurrentThread".equals(operation.getName())) return held;
                                        if ("unlock".equals(operation.getName())) { unlocks++; held = false; return null; }
                                        throw new AssertionError("예상하지 않은 잠금 호출: " + operation.getName());
                                    });
                        }
                        throw new AssertionError("예상하지 않은 Redisson 호출: " + method.getName());
                    });
        }
    }
}
