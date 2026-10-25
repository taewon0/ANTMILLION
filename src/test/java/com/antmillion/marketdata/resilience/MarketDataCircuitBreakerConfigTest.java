package com.antmillion.marketdata.resilience;

import com.antmillion.marketdata.config.MarketDataCircuitBreakerConfig;
import org.junit.Test;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.Assert.*;

/** CB 설정만 등록합니다. 개발 properties·전체 context·DB·스케줄러는 로드하지 않습니다. */
public class MarketDataCircuitBreakerConfigTest {
    @Configuration
    @Import({MarketDataCircuitBreakerConfig.class, MarketDataCircuitBreakers.class})
    static class Imported { }

    @Test
    public void scanAndImportShareOneRegistryWithoutIo() {
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            context.scan("com.antmillion.marketdata.resilience");
            context.refresh();
            assertEquals(1, context.getBeansOfType(MarketDataCircuitBreakers.class).size());
            assertEquals(1, context.getBeansOfType(MarketDataCircuitBreakerConfig.class).size());
            MarketDataCircuitBreakerConfig config = context.getBean(MarketDataCircuitBreakerConfig.class);
            assertTrue(config.isEnabled());
            assertEquals(10, config.getConfig().getSlidingWindowSize());
            assertEquals(5, config.getConfig().getMinimumNumberOfCalls());
            assertEquals(50f, config.getConfig().getFailureRateThreshold(), 0f);
            assertEquals(Long.valueOf(30000), config.getConfig().getWaitIntervalFunctionInOpenState().apply(1));
            assertEquals(1, config.getConfig().getPermittedNumberOfCallsInHalfOpenState());
            assertFalse(config.getConfig().isAutomaticTransitionFromOpenToHalfOpenEnabled());
        }
    }

    @Test
    public void explicitPropertiesAreFixedUntilRestart() {
        try (AnnotationConfigApplicationContext context = context(Map.of(
                "marketdata.circuit-breaker.enabled", "false", "marketdata.circuit-breaker.window-size", "20",
                "marketdata.circuit-breaker.minimum-calls", "8", "marketdata.circuit-breaker.failure-rate", "75",
                "marketdata.circuit-breaker.open-seconds", "60", "marketdata.circuit-breaker.half-open-calls", "2"))) {
            context.refresh();
            MarketDataCircuitBreakerConfig config = context.getBean(MarketDataCircuitBreakerConfig.class);
            assertFalse(config.isEnabled());
            assertEquals(20, config.getConfig().getSlidingWindowSize());
            assertEquals(8, config.getConfig().getMinimumNumberOfCalls());
            assertEquals(75f, config.getConfig().getFailureRateThreshold(), 0f);
            assertEquals(Long.valueOf(60000), config.getConfig().getWaitIntervalFunctionInOpenState().apply(1));
            assertEquals(2, config.getConfig().getPermittedNumberOfCallsInHalfOpenState());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("changed",
                    Map.of("marketdata.circuit-breaker.enabled", "true")));
            assertFalse(config.isEnabled());
        }
    }

    @Test
    public void invalidAndMalformedPropertiesRejectStartupEvenWhenDisabled() {
        for (Map<String, Object> values : java.util.List.<Map<String, Object>>of(
                Map.of("marketdata.circuit-breaker.minimum-calls", "11"),
                Map.of("marketdata.circuit-breaker.failure-rate", "NaN"),
                Map.of("marketdata.circuit-breaker.open-seconds", "bad"),
                Map.of("marketdata.circuit-breaker.enabled", "false", "marketdata.circuit-breaker.half-open-calls", "0"))) {
            try (AnnotationConfigApplicationContext context = context(values)) {
                assertThrows(BeansException.class, context::refresh);
            }
        }
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> values) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic", values));
        context.registerBean(PropertySourcesPlaceholderConfigurer.class, PropertySourcesPlaceholderConfigurer::new);
        context.register(Imported.class);
        return context;
    }
}
