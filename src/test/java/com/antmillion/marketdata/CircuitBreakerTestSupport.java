package com.antmillion.marketdata;

import com.antmillion.marketdata.config.MarketDataCircuitBreakerConfig;
import com.antmillion.marketdata.resilience.MarketDataCircuitBreakers;

/** 기존 격리 fixture도 운영과 같은 기본 CB 정책을 사용합니다. */
public final class CircuitBreakerTestSupport {
    private CircuitBreakerTestSupport() { }

    public static MarketDataCircuitBreakers defaults() {
        return new MarketDataCircuitBreakers(new MarketDataCircuitBreakerConfig());
    }
}
