package com.antmillion.marketdata.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Provider별 REST CB에 같은 초기 정책을 적용하며 변경 후 재시작합니다. */
@Component
public class MarketDataCircuitBreakerConfig {
    private final boolean enabled;
    private final CircuitBreakerConfig config;

    public MarketDataCircuitBreakerConfig() {
        this(true, 10, 5, 50, 30, 1);
    }

    @Autowired
    public MarketDataCircuitBreakerConfig(
            @Value("${marketdata.circuit-breaker.enabled:true}") boolean enabled,
            @Value("${marketdata.circuit-breaker.window-size:10}") int windowSize,
            @Value("${marketdata.circuit-breaker.minimum-calls:5}") int minimumCalls,
            @Value("${marketdata.circuit-breaker.failure-rate:50}") float failureRate,
            @Value("${marketdata.circuit-breaker.open-seconds:30}") long openSeconds,
            @Value("${marketdata.circuit-breaker.half-open-calls:1}") int halfOpenCalls) {
        if (windowSize < 1 || windowSize > 10000 || minimumCalls < 1 || minimumCalls > windowSize
                || !Float.isFinite(failureRate) || failureRate <= 0 || failureRate > 100
                || openSeconds < 1 || openSeconds > 86400 || halfOpenCalls < 1 || halfOpenCalls > 100) {
            throw new IllegalArgumentException("시세 Circuit Breaker 설정이 유효하지 않습니다.");
        }
        this.enabled = enabled;
        this.config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize).minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRate).waitDurationInOpenState(Duration.ofSeconds(openSeconds))
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build();
    }

    public boolean isEnabled() { return enabled; }
    public CircuitBreakerConfig getConfig() { return config; }
}
