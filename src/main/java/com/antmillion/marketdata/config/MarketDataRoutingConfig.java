package com.antmillion.marketdata.config;

import com.antmillion.marketdata.provider.CurrentPriceProvider;
import com.antmillion.marketdata.provider.InvestorFlowProvider;
import com.antmillion.marketdata.provider.MarketDataProviderRegistration;
import com.antmillion.marketdata.provider.MarketDataProviderRegistry;
import com.antmillion.marketdata.provider.MarketIndexChartProvider;
import com.antmillion.marketdata.provider.MinuteChartProvider;
import com.antmillion.marketdata.provider.PeriodChartProvider;
import com.antmillion.marketdata.provider.StockRankingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/** 기존 사용처에는 선택된 adapter를 우선 주입하며, 여섯 필수 계약을 기동 시 확인합니다. */
@Configuration
public class MarketDataRoutingConfig {
    @Bean
    public MarketDataProviderRegistry marketDataProviderRegistry(
            @Value("${marketdata.provider:kis}") String selectedId,
            List<MarketDataProviderRegistration> registrations) {
        return new MarketDataProviderRegistry(selectedId, registrations);
    }

    @Bean
    @Primary
    public CurrentPriceProvider selectedCurrentPriceProvider(MarketDataProviderRegistry registry) {
        return registry.getProvider(CurrentPriceProvider.class);
    }

    @Bean
    @Primary
    public PeriodChartProvider selectedPeriodChartProvider(MarketDataProviderRegistry registry) {
        return registry.getProvider(PeriodChartProvider.class);
    }

    @Bean
    @Primary
    public MinuteChartProvider selectedMinuteChartProvider(MarketDataProviderRegistry registry) {
        return registry.getProvider(MinuteChartProvider.class);
    }

    @Bean
    @Primary
    public MarketIndexChartProvider selectedMarketIndexChartProvider(MarketDataProviderRegistry registry) {
        return registry.getProvider(MarketIndexChartProvider.class);
    }

    @Bean
    @Primary
    public StockRankingProvider selectedStockRankingProvider(MarketDataProviderRegistry registry) {
        return registry.getProvider(StockRankingProvider.class);
    }

    @Bean
    @Primary
    public InvestorFlowProvider selectedInvestorFlowProvider(MarketDataProviderRegistry registry) {
        return registry.getProvider(InvestorFlowProvider.class);
    }
}
