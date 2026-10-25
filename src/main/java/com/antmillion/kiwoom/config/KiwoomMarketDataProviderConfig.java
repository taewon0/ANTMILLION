package com.antmillion.kiwoom.config;

import com.antmillion.kiwoom.repository.KiwoomCurrentPriceRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomPeriodChartRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomDailyMinuteRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomMarketIndexChartRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomStockRankingRedisRepository;
import com.antmillion.kiwoom.repository.KiwoomInvestorFlowRedisRepository;
import com.antmillion.kiwoom.service.KiwoomCurrentPriceProvider;
import com.antmillion.kiwoom.service.KiwoomPeriodChartProvider;
import com.antmillion.kiwoom.service.KiwoomMinuteChartProvider;
import com.antmillion.kiwoom.service.KiwoomMarketIndexChartProvider;
import com.antmillion.kiwoom.service.KiwoomStockRankingProvider;
import com.antmillion.kiwoom.service.KiwoomInvestorFlowProvider;
import com.antmillion.kiwoom.service.KiwoomRestRateLimiter;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
import com.antmillion.marketdata.provider.PeriodChartProvider;
import com.antmillion.marketdata.provider.MinuteChartProvider;
import com.antmillion.marketdata.provider.MarketIndexChartProvider;
import com.antmillion.marketdata.provider.StockRankingProvider;
import com.antmillion.marketdata.provider.InvestorFlowProvider;
import com.antmillion.marketdata.provider.MarketDataProviderRegistration;
import com.antmillion.marketdata.config.MarketDataCircuitBreakerConfig;
import com.antmillion.marketdata.resilience.MarketDataCircuitBreakers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;

/** 인증 활성화 시 여섯 REST 계약을 등록하며 실제 선택은 marketdata.provider로 결정합니다. */
@Configuration
@Import({KiwoomAuthConfig.class, MarketDataCircuitBreakerConfig.class, MarketDataCircuitBreakers.class})
public class KiwoomMarketDataProviderConfig {
    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public KiwoomCurrentPriceRedisRepository kiwoomCurrentPriceRedisRepository(RedisTemplate<String, Object> redis) {
        return new KiwoomCurrentPriceRedisRepository(redis, Clock.systemUTC());
    }

    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public KiwoomCurrentPriceProvider kiwoomCurrentPriceProvider(KiwoomTokenService tokens,
            @Qualifier("kiwoomAuthRestTemplate") RestTemplate rest, KiwoomRestRateLimiter limiter,
            KiwoomCurrentPriceRedisRepository cache, MarketDataCircuitBreakers circuitBreakers) {
        return new KiwoomCurrentPriceProvider(tokens, rest, limiter, cache, circuitBreakers);
    }

    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public KiwoomPeriodChartRedisRepository kiwoomPeriodChartRedisRepository(RedisTemplate<String, Object> redis) {
        return new KiwoomPeriodChartRedisRepository(redis, Clock.systemUTC());
    }

    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public KiwoomPeriodChartProvider kiwoomPeriodChartProvider(KiwoomTokenService tokens,
            @Qualifier("kiwoomAuthRestTemplate") RestTemplate rest, KiwoomRestRateLimiter limiter,
            KiwoomPeriodChartRedisRepository cache, MarketDataCircuitBreakers circuitBreakers) {
        return new KiwoomPeriodChartProvider(tokens, rest, limiter, cache, circuitBreakers);
    }

    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public KiwoomDailyMinuteRedisRepository kiwoomDailyMinuteRedisRepository(RedisTemplate<String, Object> redis) {
        return new KiwoomDailyMinuteRedisRepository(redis, Clock.systemUTC());
    }

    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public KiwoomMinuteChartProvider kiwoomMinuteChartProvider(KiwoomTokenService tokens,
            @Qualifier("kiwoomAuthRestTemplate") RestTemplate rest, KiwoomRestRateLimiter limiter,
            KiwoomDailyMinuteRedisRepository cache, MarketDataCircuitBreakers circuitBreakers) {
        return new KiwoomMinuteChartProvider(tokens, rest, limiter, cache, circuitBreakers);
    }

    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public KiwoomMarketIndexChartRedisRepository kiwoomMarketIndexChartRedisRepository(RedisTemplate<String, Object> redis) {
        return new KiwoomMarketIndexChartRedisRepository(redis, Clock.systemUTC());
    }

    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public KiwoomMarketIndexChartProvider kiwoomMarketIndexChartProvider(KiwoomTokenService tokens,
            @Qualifier("kiwoomAuthRestTemplate") RestTemplate rest, KiwoomRestRateLimiter limiter,
            KiwoomMarketIndexChartRedisRepository cache) {
        return new KiwoomMarketIndexChartProvider(tokens, rest, limiter, cache);
    }

    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public KiwoomStockRankingRedisRepository kiwoomStockRankingRedisRepository(RedisTemplate<String, Object> redis) {
        return new KiwoomStockRankingRedisRepository(redis, Clock.systemUTC());
    }

    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public KiwoomStockRankingProvider kiwoomStockRankingProvider(KiwoomTokenService tokens,
            @Qualifier("kiwoomAuthRestTemplate") RestTemplate rest, KiwoomRestRateLimiter limiter,
            KiwoomStockRankingRedisRepository cache) {
        return new KiwoomStockRankingProvider(tokens, rest, limiter, cache);
    }

    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public KiwoomInvestorFlowRedisRepository kiwoomInvestorFlowRedisRepository(RedisTemplate<String, Object> redis) {
        return new KiwoomInvestorFlowRedisRepository(redis, Clock.systemUTC());
    }

    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public KiwoomInvestorFlowProvider kiwoomInvestorFlowProvider(KiwoomTokenService tokens,
            @Qualifier("kiwoomAuthRestTemplate") RestTemplate rest, KiwoomRestRateLimiter limiter,
            KiwoomInvestorFlowRedisRepository cache) {
        return new KiwoomInvestorFlowProvider(tokens, rest, limiter, cache);
    }

    @Bean
    @Conditional(KiwoomAuthConfig.Enabled.class)
    public MarketDataProviderRegistration kiwoomMarketDataProviderRegistration(
            KiwoomCurrentPriceProvider currentPrice, KiwoomPeriodChartProvider periodChart,
            KiwoomMinuteChartProvider minuteChart, KiwoomMarketIndexChartProvider marketIndexChart,
            KiwoomStockRankingProvider stockRanking, KiwoomInvestorFlowProvider investorFlow) {
        return MarketDataProviderRegistration.builder("kiwoom")
                .add(CurrentPriceProvider.class, currentPrice)
                .add(PeriodChartProvider.class, periodChart)
                .add(MinuteChartProvider.class, minuteChart)
                .add(MarketIndexChartProvider.class, marketIndexChart)
                .add(StockRankingProvider.class, stockRanking)
                .add(InvestorFlowProvider.class, investorFlow)
                .build();
    }
}
