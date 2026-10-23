package com.antmillion.kiwoom.service;

import com.antmillion.kiwoom.config.KiwoomRestRateLimitConfig;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import io.github.bucket4j.TimeMeter;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** 단조 시각·합성 인증 범위만 사용하며 Redis/HTTP/DB/개발 설정을 로드하지 않습니다. */
public class KiwoomRestRateLimitTest {
    private final AtomicLong nanos = new AtomicLong();
    private final TimeMeter clock = new TimeMeter() {
        @Override public long currentTimeNanos() { return nanos.get(); }
        @Override public boolean isWallClockBased() { return false; }
    };
    private final KiwoomRestRateLimiter limiter = withClock(clock);

    // 기존 응답 계약 회귀도 실제 limiter에 별도의 합성 단조 시각을 주입합니다.
    public static KiwoomRestRateLimiter withClock(TimeMeter clock) {
        return new KiwoomRestRateLimiter(new KiwoomRestRateLimitConfig(5, 1), clock);
    }

    @Test
    public void defaultRealBudgetAndGreedyRefillRespectMonotonicBoundaries() {
        KiwoomTokenService real = tokens("real", "key-a", "secret-a");
        for (int i = 0; i < 5; i++) limiter.acquireCurrentPrice(real);
        KiwoomRateLimitExceededException error = rejected(real);
        assertEquals(200_000_000L, error.getNanosToWait());
        assertEquals(1, error.getRetryAfterSeconds());
        nanos.set(199_999_999L);
        assertEquals(1, rejected(real).getNanosToWait());
        nanos.incrementAndGet();
        limiter.acquireCurrentPrice(real);
        rejected(real);
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(10));
        for (int i = 0; i < 5; i++) limiter.acquireCurrentPrice(real);
        rejected(real); // 오래 쉬어도 설정된 capacity 이상 누적하지 않습니다.
    }

    @Test
    public void virtualBudgetRefillsOnlyAfterOneSecond() {
        KiwoomTokenService virtual = tokens("virtual", "key-a", "secret-a");
        limiter.acquireCurrentPrice(virtual);
        assertEquals(1_000_000_000L, rejected(virtual).getNanosToWait());
        nanos.set(999_999_999L);
        assertEquals(1, rejected(virtual).getNanosToWait());
        nanos.incrementAndGet();
        limiter.acquireCurrentPrice(virtual);
        rejected(virtual);
    }

    @Test
    public void sameEnvironmentAndAppKeyShareAcrossInstancesAndSecretRotation() {
        limiter.acquireCurrentPrice(tokens("virtual", "key-a", "secret-a"));
        rejected(tokens("virtual", "key-a", "rotated-secret"));
        rejected(tokens("VIRTUAL", "key-a", "secret-a"));
        // 앱 키가 다른 같은 계좌의 예산 병합은 이번 범위에서 지원하지 않습니다.
        limiter.acquireCurrentPrice(tokens("virtual", "key-b", "secret-a"));
        limiter.acquireCurrentPrice(tokens("real", "key-a", "secret-a"));
    }

    @Test
    public void eitherEnvironmentCanBeExhaustedWithoutSpendingTheOther() {
        for (String mode : List.of("real", "virtual")) {
            KiwoomTokenService first = tokens(mode, mode, "secret");
            for (int i = 0; i < ("real".equals(mode) ? 5 : 1); i++) limiter.acquireCurrentPrice(first);
            rejected(first);
            limiter.acquireCurrentPrice(tokens("real".equals(mode) ? "virtual" : "real", mode, "secret"));
        }
    }

    @Test
    public void identityHidesRawValuesKeepsBoundariesAndPreservesTokenKeys() {
        KiwoomAccessTokenScope first = new KiwoomAccessTokenScope("real", "synthetic-key", "synthetic-secret");
        KiwoomAccessTokenScope rotated = new KiwoomAccessTokenScope("real", "synthetic-key", "new-secret");
        assertEquals(first.getCurrentPriceBudgetKey(), rotated.getCurrentPriceBudgetKey());
        assertNotEquals(first.getTokenKey(), rotated.getTokenKey());
        assertTrue(first.getCurrentPriceBudgetKey().matches("[0-9a-f]{64}"));
        assertFalse(first.getCurrentPriceBudgetKey().contains("synthetic"));
        assertNotEquals(first.getCurrentPriceBudgetKey(), first.getTokenKey());
        // 기존 길이 구분 SHA-256 토큰 키 계산은 변경하지 않았습니다.
        String legacyHash = "cae381ac5dcb5674072de7af14b144a2c4033156e86732c0302c0a62fdfc741e";
        assertEquals("kiwoom:access_token:v1:" + legacyHash, first.getTokenKey());
        assertEquals("kiwoom:token:lock:v1:" + legacyHash, first.getLockKey());
        assertNotEquals(tokens("virtual", "a:b", "c").getCurrentPriceBudgetKey(),
                tokens("virtual", "a", "b:c").getCurrentPriceBudgetKey());
    }

    @Test
    public void concurrentFirstRequestsCreateOneSharedBucket() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final int caller = i;
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                        limiter.acquireCurrentPrice(tokens("real", "shared-key", "secret-" + caller));
                        accepted.incrementAndGet();
                    } catch (KiwoomRateLimitExceededException expected) {
                        // 같은 앱 키에 대해 초기 5개만 성공해야 합니다.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
            assertEquals(5, accepted.get());
            rejected(tokens("real", "shared-key", "another-secret"));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void springResolvesDefaultsLowerLimitAndSingleSharedLimiter() {
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            context.refresh();
            assertEquals(5, context.getBean(KiwoomRestRateLimitConfig.class).tokensPerSecond("https://api.kiwoom.com"));
            assertEquals(1, context.getBeansOfType(KiwoomRestRateLimiter.class).size());
        }
        try (AnnotationConfigApplicationContext context = context(Map.of("kiwoom.rest.rate-limit.real-per-second", "2"))) {
            context.refresh();
            KiwoomRestRateLimitConfig config = context.getBean(KiwoomRestRateLimitConfig.class);
            KiwoomRestRateLimiter limited = new KiwoomRestRateLimiter(config, clock);
            KiwoomTokenService real = tokens("real", "key", "secret");
            limited.acquireCurrentPrice(real);
            limited.acquireCurrentPrice(real);
            assertThrows(KiwoomRateLimitExceededException.class, () -> limited.acquireCurrentPrice(real));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("later",
                    Map.of("kiwoom.rest.rate-limit.real-per-second", "5")));
            assertEquals(2, config.tokensPerSecond(real.getBaseUrl()));
        }
    }

    @Test
    public void springRejectsInvalidLimitsWithoutExternalInfrastructure() {
        for (String property : List.of("kiwoom.rest.rate-limit.real-per-second", "kiwoom.rest.rate-limit.virtual-per-second")) {
            for (String invalid : List.of("0", "-1", "6", "2147483648", "", "bad")) {
                try (AnnotationConfigApplicationContext context = context(Map.of(property, invalid))) {
                    assertThrows(RuntimeException.class, context::refresh);
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new KiwoomRestRateLimitConfig(5, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new KiwoomRestRateLimitConfig(5, 1).tokensPerSecond("https://unexpected.test"));
    }

    @Test
    public void rejectionDoesNotExposeIdentityOrCredentials() {
        KiwoomTokenService tokens = tokens("virtual", "synthetic-key", "synthetic-secret");
        limiter.acquireCurrentPrice(tokens);
        KiwoomRateLimitExceededException error = rejected(tokens);
        assertNull(error.getCause());
        for (String raw : List.of("synthetic-key", "synthetic-secret", tokens.getCurrentPriceBudgetKey())) {
            assertFalse(error.toString().contains(raw));
        }
        assertEquals(2, new KiwoomRateLimitExceededException(1_000_000_001L).getRetryAfterSeconds());
    }

    private KiwoomRateLimitExceededException rejected(KiwoomTokenService tokens) {
        return assertThrows(KiwoomRateLimitExceededException.class, () -> limiter.acquireCurrentPrice(tokens));
    }

    private static KiwoomTokenService tokens(String mode, String key, String secret) {
        // acquire는 인증 범위 getter만 읽으므로 외부 의존성을 생성하지 않습니다.
        return new KiwoomTokenService(new KiwoomAccessTokenScope(mode, key, secret), 600,
                null, null, null, null);
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().remove("systemProperties");
        context.getEnvironment().getPropertySources().remove("systemEnvironment");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic", properties));
        context.register(PropertySourcesPlaceholderConfigurer.class, KiwoomRestRateLimitConfig.class,
                KiwoomRestRateLimiter.class);
        return context;
    }
}
