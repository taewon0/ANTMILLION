package com.antmillion.kis.config;

import com.antmillion.kis.service.KisRealtimeSubscriptionProvider;
import com.antmillion.marketdata.provider.RealtimeProviderRegistration;
import com.antmillion.marketdata.provider.RealtimeSubscriptionProvider.Kind;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Set;

/** 기존 KIS의 현재가·호가 희망 구독을 등록합니다. */
@Configuration
@Profile("!dev-mock")
public class KisRealtimeProviderConfig {
    @Bean
    public RealtimeProviderRegistration kisRealtimeProviderRegistration(KisRealtimeSubscriptionProvider provider) {
        return new RealtimeProviderRegistration("kis", provider, Set.of(Kind.CURRENT_PRICE, Kind.ORDER_BOOK));
    }
}
