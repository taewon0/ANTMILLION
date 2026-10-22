package com.antmillion.kis.config;

import com.antmillion.kis.service.KisCurrentPriceProvider;
import com.antmillion.kis.service.KisInvestorFlowProvider;
import com.antmillion.kis.service.KisMarketIndexChartProvider;
import com.antmillion.kis.service.KisMinuteChartProvider;
import com.antmillion.kis.service.KisPeriodChartProvider;
import com.antmillion.kis.service.KisStockRankingProvider;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
import com.antmillion.marketdata.provider.InvestorFlowProvider;
import com.antmillion.marketdata.provider.MarketDataProviderRegistration;
import com.antmillion.marketdata.provider.MarketIndexChartProvider;
import com.antmillion.marketdata.provider.MinuteChartProvider;
import com.antmillion.marketdata.provider.PeriodChartProvider;
import com.antmillion.marketdata.provider.StockRankingProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** KIS가 지원하는 REST 계약만 등록합니다. 토큰·HTTP·캐시·제한은 기존 adapter에 둡니다. */
@Configuration
public class KisMarketDataProviderConfig {
    @Bean
    public MarketDataProviderRegistration kisMarketDataProviderRegistration(
            KisCurrentPriceProvider currentPrice, KisPeriodChartProvider periodChart,
            KisMinuteChartProvider minuteChart, KisMarketIndexChartProvider marketIndexChart,
            KisStockRankingProvider stockRanking, KisInvestorFlowProvider investorFlow) {
        return MarketDataProviderRegistration.builder("kis")
                .add(CurrentPriceProvider.class, currentPrice)
                .add(PeriodChartProvider.class, periodChart)
                .add(MinuteChartProvider.class, minuteChart)
                .add(MarketIndexChartProvider.class, marketIndexChart)
                .add(StockRankingProvider.class, stockRanking)
                .add(InvestorFlowProvider.class, investorFlow)
                .build();
    }
}
