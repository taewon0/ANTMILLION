package com.antmillion.marketdata.config;

import com.antmillion.marketdata.provider.RealtimeProviderRegistration;
import com.antmillion.marketdata.provider.RealtimeProviderRegistry;
import com.antmillion.marketdata.provider.RealtimeSubscriptionProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.List;

/** REST 선택과 독립적으로 화면·주문이 공유할 WS 구독 구현을 선택합니다. */
@Configuration
@Profile("!dev-mock")
public class RealtimeRoutingConfig {
    @Bean
    public RealtimeProviderRegistry realtimeProviderRegistry(
            @Value("${marketdata.realtime-provider:kis}") String selectedId,
            List<RealtimeProviderRegistration> registrations) {
        return new RealtimeProviderRegistry(selectedId, registrations);
    }

    @Bean
    @Primary
    public RealtimeSubscriptionProvider selectedRealtimeSubscriptionProvider(RealtimeProviderRegistry registry) {
        return registry.getProvider();
    }
}
