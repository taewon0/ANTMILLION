package com.antmillion.marketdata.resilience;

import com.antmillion.kis.exception.KisMarketDataException;
import com.antmillion.kiwoom.exception.KiwoomMarketDataException;
import com.antmillion.marketdata.config.MarketDataCircuitBreakerConfig;
import com.antmillion.marketdata.exception.MarketDataUnavailableException;
import com.antmillion.marketdata.resilience.MarketDataCircuitBreakers.Provider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 실제 CB 상태 머신에 합성 시각·호출만 연결합니다. 대기/외부 I/O는 없습니다. */
public class MarketDataCircuitBreakersTest {
    private final MutableClock clock = new MutableClock();
    private final MarketDataCircuitBreakers breakers = new MarketDataCircuitBreakers(new MarketDataCircuitBreakerConfig(), clock);
    private static final String REAL = "https://real.test";
    private static final String VIRTUAL = "https://virtual.test";

    @Test
    public void defaultMinimumFiveAndFiftyPercentAreInclusive() {
        for (int i = 0; i < 2; i++) fail(Provider.KIS, REAL);
        succeed(Provider.KIS, REAL);
        succeed(Provider.KIS, REAL);
        assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
        fail(Provider.KIS, REAL);
        assertEquals(CircuitBreaker.State.OPEN, circuit().getState());
        assertEquals(5, circuit().getMetrics().getNumberOfBufferedCalls());
        assertEquals(3, circuit().getMetrics().getNumberOfFailedCalls());
        assertThrows(MarketDataUnavailableException.class, () -> breakers.acquire(Provider.KIS, REAL));
    }

    @Test
    public void exactFiftyPercentOpensAndOldestSamplesLeaveTenCallWindow() {
        for (int i = 0; i < 10; i++) succeed(Provider.KIS, REAL);
        for (int i = 0; i < 4; i++) fail(Provider.KIS, REAL);
        assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
        assertEquals(10, circuit().getMetrics().getNumberOfBufferedCalls());
        fail(Provider.KIS, REAL);
        assertEquals(CircuitBreaker.State.OPEN, circuit().getState());
        assertEquals(50f, circuit().getMetrics().getFailureRate(), 0f);
    }

    @Test
    public void providerAndActualEnvironmentAreIndependentButSameScopeIsShared() {
        open();
        assertSame(circuit(), breakers.circuit(Provider.KIS, REAL));
        for (Provider provider : Provider.values()) {
            for (String base : List.of(REAL, VIRTUAL)) {
                if (provider == Provider.KIS && base.equals(REAL)) continue;
                succeed(provider, base);
                assertEquals(CircuitBreaker.State.CLOSED, breakers.circuit(provider, base).getState());
                assertEquals(1, breakers.circuit(provider, base).getMetrics().getNumberOfSuccessfulCalls());
            }
        }
    }

    @Test
    public void thirtySecondBoundaryIsLazyAndSuccessfulProbeClosesWithNewWindow() {
        open();
        clock.advance(29);
        assertThrows(MarketDataUnavailableException.class, () -> breakers.acquire(Provider.KIS, REAL));
        clock.advance(1);
        // 1.7.1은 대기 시각을 엄격히 지난 첫 호출에서 탐색합니다.
        assertThrows(MarketDataUnavailableException.class, () -> breakers.acquire(Provider.KIS, REAL));
        clock.tick();
        assertEquals(CircuitBreaker.State.OPEN, circuit().getState());
        succeed(Provider.KIS, REAL);
        assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
        assertEquals(0, circuit().getMetrics().getNumberOfBufferedCalls());
        succeed(Provider.KIS, REAL);
        assertEquals(1, circuit().getMetrics().getNumberOfSuccessfulCalls());
    }

    @Test
    public void failedProbeReopensForFullWait() {
        open();
        clock.advance(30); clock.tick();
        fail(Provider.KIS, REAL);
        assertEquals(CircuitBreaker.State.OPEN, circuit().getState());
        clock.advance(29);
        assertThrows(MarketDataUnavailableException.class, () -> breakers.acquire(Provider.KIS, REAL));
        clock.advance(1); clock.tick();
        succeed(Provider.KIS, REAL);
        assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
    }

    @Test
    public void ignoredAndUnusedCallsDoNotEnterDenominatorOrConsumeHalfOpenPermit() {
        try (MarketDataCircuitBreakers.Call ignored = breakers.acquire(Provider.KIS, REAL)) { }
        try (MarketDataCircuitBreakers.Call call = breakers.acquire(Provider.KIS, REAL)) {
            assertThrows(KisMarketDataException.class, () -> call.execute(() -> {
                throw KisMarketDataException.tokenAccess("safe");
            }));
        }
        assertEquals(0, circuit().getMetrics().getNumberOfBufferedCalls());
        open();
        clock.advance(30); clock.tick();
        for (int i = 0; i < 3; i++) {
            try (MarketDataCircuitBreakers.Call ignored = breakers.acquire(Provider.KIS, REAL)) { }
        }
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuit().getState());
        assertEquals(0, circuit().getMetrics().getNumberOfBufferedCalls());
        try (MarketDataCircuitBreakers.Call call = breakers.acquire(Provider.KIS, REAL)) {
            assertThrows(IllegalArgumentException.class, () -> call.execute(() -> { throw new IllegalArgumentException("ignored"); }));
        }
        succeed(Provider.KIS, REAL);
        assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
    }

    @Test
    public void errorsAndDoubleCloseReleasePermissionOnlyOnce() {
        open();
        clock.advance(30); clock.tick();
        MarketDataCircuitBreakers.Call abandoned = breakers.acquire(Provider.KIS, REAL);
        assertThrows(AssertionError.class, () -> abandoned.execute(() -> { throw new AssertionError("synthetic"); }));
        abandoned.close();
        abandoned.close();
        try (MarketDataCircuitBreakers.Call active = breakers.acquire(Provider.KIS, REAL)) {
            assertThrows(MarketDataUnavailableException.class, () -> breakers.acquire(Provider.KIS, REAL));
            active.execute(() -> 1);
            assertThrows(IllegalStateException.class, () -> active.execute(() -> 2));
        }
    }

    @Test
    public void concurrentHalfOpenRequestsAllowOnlyOneProbeAndOtherProviderContinues() throws Exception {
        open();
        clock.advance(30); clock.tick();
        ExecutorService workers = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch rejected = new CountDownLatch(11);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) futures.add(workers.submit(() -> {
                await(start);
                try (MarketDataCircuitBreakers.Call call = breakers.acquire(Provider.KIS, REAL)) {
                    allowed.incrementAndGet();
                    acquired.countDown();
                    await(finish);
                    call.execute(() -> 1);
                } catch (MarketDataUnavailableException expected) { denied.incrementAndGet(); rejected.countDown(); }
            }));
            start.countDown();
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            assertTrue(rejected.await(5, TimeUnit.SECONDS));
            assertEquals(11, denied.get());
            succeed(Provider.KIWOOM, REAL);
            finish.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(1, allowed.get());
            assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
        } finally { finish.countDown(); workers.shutdownNow(); }
    }

    @Test
    public void concurrentClosedFailuresAndRegistryCreationRemainSafe() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch complete = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) futures.add(workers.submit(() -> {
                try (MarketDataCircuitBreakers.Call call = breakers.acquire(Provider.KIS, REAL)) {
                    ready.countDown();
                    await(complete);
                    assertThrows(KisMarketDataException.class, () -> call.execute(() -> { throw failure(); }));
                }
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            complete.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(CircuitBreaker.State.OPEN, circuit().getState());
            assertThrows(MarketDataUnavailableException.class, () -> breakers.acquire(Provider.KIS, REAL));
        } finally { complete.countDown(); workers.shutdownNow(); }
    }

    @Test
    public void disabledPolicyPreservesCallsDespiteFailures() {
        MarketDataCircuitBreakers disabled = new MarketDataCircuitBreakers(
                new MarketDataCircuitBreakerConfig(false, 10, 5, 50, 30, 1), clock);
        for (int i = 0; i < 20; i++) {
            try (MarketDataCircuitBreakers.Call call = disabled.acquire(Provider.KIWOOM, VIRTUAL)) {
                assertThrows(KiwoomMarketDataException.class, () -> call.execute(() -> {
                    throw KiwoomMarketDataException.fromHttp(new ResourceAccessException("private"), "safe");
                }));
            }
        }
    }

    @Test
    public void lateUnusedClosedPermitDoesNotIncreaseNewHalfOpenProbeLimit() {
        MarketDataCircuitBreakers.Call old = breakers.acquire(Provider.KIS, REAL);
        open();
        clock.advance(30); clock.tick();
        try (MarketDataCircuitBreakers.Call probe = breakers.acquire(Provider.KIS, REAL)) {
            old.close();
            assertThrows(MarketDataUnavailableException.class, () -> breakers.acquire(Provider.KIS, REAL));
            probe.execute(() -> 1);
            assertEquals(CircuitBreaker.State.CLOSED, circuit().getState());
        } finally { old.close(); }
    }

    @Test
    public void lateClosedSuccessCannotCompleteHalfOpenProbeOrResetItsFailures() {
        try (MarketDataCircuitBreakers.Call old = breakers.acquire(Provider.KIS, REAL)) {
            open();
            clock.advance(30); clock.tick();
            try (MarketDataCircuitBreakers.Call probe = breakers.acquire(Provider.KIS, REAL)) {
                old.execute(() -> 1);
                assertEquals(CircuitBreaker.State.HALF_OPEN, circuit().getState());
                assertEquals(0, circuit().getMetrics().getNumberOfBufferedCalls());
                assertThrows(KisMarketDataException.class, () -> probe.execute(() -> { throw failure(); }));
                assertEquals(CircuitBreaker.State.OPEN, circuit().getState());
            }
        }
    }

    @Test
    public void invalidConfigurationIsRejectedInsteadOfSilentlyClamped() {
        for (int[] values : new int[][]{{0, 5, 50, 30, 1}, {10, 11, 50, 30, 1}, {10, 0, 50, 30, 1},
                {10, 5, 0, 30, 1}, {10, 5, 101, 30, 1}, {10, 5, 50, 0, 1}, {10, 5, 50, 86401, 1},
                {10, 5, 50, 30, 0}, {10001, 5, 50, 30, 1}, {10, 5, 50, 30, 101}}) {
            assertThrows(IllegalArgumentException.class, () -> new MarketDataCircuitBreakerConfig(
                    true, values[0], values[1], values[2], values[3], values[4]));
        }
        assertThrows(IllegalArgumentException.class, () -> new MarketDataCircuitBreakerConfig(true, 10, 5, Float.NaN, 30, 1));
    }

    @Test
    public void configuredTwoProbesWaitForBothAndApplyFailureThreshold() {
        MarketDataCircuitBreakers two = new MarketDataCircuitBreakers(
                new MarketDataCircuitBreakerConfig(true, 10, 5, 50, 30, 2), clock);
        for (int i = 0; i < 5; i++) {
            try (MarketDataCircuitBreakers.Call call = two.acquire(Provider.KIS, REAL)) {
                assertThrows(KisMarketDataException.class, () -> call.execute(() -> { throw failure(); }));
            }
        }
        clock.advance(30); clock.tick();
        try (MarketDataCircuitBreakers.Call first = two.acquire(Provider.KIS, REAL);
             MarketDataCircuitBreakers.Call second = two.acquire(Provider.KIS, REAL)) {
            assertThrows(MarketDataUnavailableException.class, () -> two.acquire(Provider.KIS, REAL));
            first.execute(() -> 1);
            assertEquals(CircuitBreaker.State.HALF_OPEN, two.circuit(Provider.KIS, REAL).getState());
            assertThrows(KisMarketDataException.class, () -> second.execute(() -> { throw failure(); }));
            assertEquals(CircuitBreaker.State.OPEN, two.circuit(Provider.KIS, REAL).getState());
        }
        clock.advance(30); clock.tick();
        try (MarketDataCircuitBreakers.Call first = two.acquire(Provider.KIS, REAL);
             MarketDataCircuitBreakers.Call second = two.acquire(Provider.KIS, REAL)) {
            first.execute(() -> 1);
            second.execute(() -> 1);
            assertEquals(CircuitBreaker.State.CLOSED, two.circuit(Provider.KIS, REAL).getState());
        }
    }

    private CircuitBreaker circuit() { return breakers.circuit(Provider.KIS, REAL); }
    private void open() { for (int i = 0; i < 5; i++) fail(Provider.KIS, REAL); }
    private void succeed(Provider provider, String base) {
        try (MarketDataCircuitBreakers.Call call = breakers.acquire(provider, base)) { assertEquals(1, (int) call.execute(() -> 1)); }
    }
    private void fail(Provider provider, String base) {
        try (MarketDataCircuitBreakers.Call call = breakers.acquire(provider, base)) {
            assertThrows(KisMarketDataException.class, () -> call.execute(() -> { throw failure(); }));
        }
    }
    private static KisMarketDataException failure() {
        return KisMarketDataException.fromHttp(new HttpServerErrorException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE), "safe");
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-25T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        void tick() { now = now.plusNanos(1); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
