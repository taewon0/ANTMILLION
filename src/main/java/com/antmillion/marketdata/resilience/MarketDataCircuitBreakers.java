package com.antmillion.marketdata.resilience;

import com.antmillion.kis.exception.KisMarketDataException;
import com.antmillion.kiwoom.exception.KiwoomMarketDataException;
import com.antmillion.marketdata.config.MarketDataCircuitBreakerConfig;
import com.antmillion.marketdata.exception.MarketDataUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.internal.CircuitBreakerStateMachine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** JVM 안에서 Provider·실제 요청 주소별 REST 상태를 공유합니다. 종목/API별로 나누지 않습니다. */
@Component
public class MarketDataCircuitBreakers {
    public enum Provider { KIS, KIWOOM }

    private final MarketDataCircuitBreakerConfig config;
    private final Clock clock;
    private final ConcurrentMap<String, Guard> circuits = new ConcurrentHashMap<>();

    @Autowired
    public MarketDataCircuitBreakers(MarketDataCircuitBreakerConfig config) {
        this(config, Clock.systemUTC());
    }

    public MarketDataCircuitBreakers(MarketDataCircuitBreakerConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    CircuitBreaker circuit(Provider provider, String baseUrl) {
        return guard(provider, baseUrl).circuit;
    }

    private Guard guard(Provider provider, String baseUrl) {
        Objects.requireNonNull(provider, "provider");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("시세 요청 주소가 필요합니다.");
        return circuits.computeIfAbsent(provider.name() + ":" + baseUrl,
                key -> new Guard(new CircuitBreakerStateMachine(provider.name() + "-REST", config.getConfig(), clock)));
    }

    /** 한도·토큰 조회 전에 허가를 확보하며 미사용 허가는 close에서 반환합니다. */
    public Call acquire(Provider provider, String baseUrl) {
        if (!config.isEnabled()) return new Call(null, 0);
        return guard(provider, baseUrl).acquire();
    }

    /** 전환 전 요청의 늦은 완료/허가 반환이 새 HALF_OPEN 탐색을 완료시키거나 늘리지 않게 합니다. */
    private static final class Guard {
        private final CircuitBreaker circuit;
        private long generation;

        private Guard(CircuitBreaker circuit) { this.circuit = circuit; }

        synchronized Call acquire() {
            CircuitBreaker.State before = circuit.getState();
            if (!circuit.tryAcquirePermission()) throw new MarketDataUnavailableException();
            if (before != circuit.getState()) generation++;
            return new Call(this, generation);
        }

        synchronized void complete(long acquiredGeneration, boolean success, RuntimeException failure) {
            if (generation != acquiredGeneration) return;
            CircuitBreaker.State before = circuit.getState();
            // 실패율만 사용합니다. 토큰 대기/지연은 느린 호출 집계에 포함하지 않습니다.
            if (success) circuit.onSuccess(0, TimeUnit.NANOSECONDS);
            else if (failure != null) circuit.onError(0, TimeUnit.NANOSECONDS, failure);
            else circuit.releasePermission();
            if (before != circuit.getState()) generation++;
        }
    }

    private static boolean providerFailure(RuntimeException failure) {
        if (failure instanceof KisMarketDataException) {
            KisMarketDataException.Kind kind = ((KisMarketDataException) failure).getKind();
            return kind == KisMarketDataException.Kind.SERVER || kind == KisMarketDataException.Kind.TRANSPORT;
        }
        if (failure instanceof KiwoomMarketDataException) {
            KiwoomMarketDataException.Kind kind = ((KiwoomMarketDataException) failure).getKind();
            return kind == KiwoomMarketDataException.Kind.SERVER || kind == KiwoomMarketDataException.Kind.TRANSPORT;
        }
        return false;
    }

    /** 요청 스레드에서 한 번만 사용합니다. 캐시 반환/로컬 거절은 실행하지 않아 집계에서 빠집니다. */
    public static final class Call implements AutoCloseable {
        private final Guard guard;
        private final long generation;
        private boolean completed;

        private Call(Guard guard, long generation) { this.guard = guard; this.generation = generation; }

        public <T> T execute(Supplier<T> remoteCall) {
            if (completed) throw new IllegalStateException("이미 완료된 시세 호출입니다.");
            try {
                T result = remoteCall.get();
                completed = true;
                if (guard != null) guard.complete(generation, true, null);
                return result;
            } catch (RuntimeException failure) {
                if (guard != null && providerFailure(failure)) {
                    completed = true;
                    guard.complete(generation, false, failure);
                }
                throw failure;
            }
        }

        @Override
        public void close() {
            if (!completed) {
                completed = true;
                if (guard != null) guard.complete(generation, false, null);
            }
        }
    }
}
