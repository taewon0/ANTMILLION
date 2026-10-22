package com.antmillion.marketdata.provider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Provider 식별자와 지원하는 REST 계약의 불변 등록 정보입니다. */
public final class MarketDataProviderRegistration {
    private static final Set<Class<?>> CONTRACTS = Set.of(CurrentPriceProvider.class, PeriodChartProvider.class,
            MinuteChartProvider.class, MarketIndexChartProvider.class, StockRankingProvider.class,
            InvestorFlowProvider.class);

    private final String id;
    private final Map<Class<?>, Object> providers;

    private MarketDataProviderRegistration(String id, Map<Class<?>, Object> providers) {
        this.id = id;
        this.providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
    }

    public static Builder builder(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Provider id must use lowercase letters, digits or hyphens");
        }
        return new Builder(id);
    }

    public String getId() {
        return id;
    }

    public Set<Class<?>> getSupportedContracts() {
        return providers.keySet();
    }

    public <T> T getProvider(Class<T> contract) {
        Objects.requireNonNull(contract, "contract");
        Object provider = providers.get(contract);
        if (provider == null) {
            throw new UnsupportedOperationException("Provider " + id + " does not support " + contract.getSimpleName());
        }
        return contract.cast(provider);
    }

    public static final class Builder {
        private final String id;
        private final Map<Class<?>, Object> providers = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = id;
        }

        public <T> Builder add(Class<T> contract, T provider) {
            if (!CONTRACTS.contains(contract)) {
                throw new IllegalArgumentException("Only the six REST market data contracts can be registered");
            }
            Objects.requireNonNull(provider, "provider");
            if (!contract.isInstance(provider)) {
                throw new IllegalArgumentException("Provider does not implement " + contract.getSimpleName());
            }
            if (providers.putIfAbsent(contract, provider) != null) {
                throw new IllegalArgumentException("Duplicate contract: " + contract.getSimpleName());
            }
            return this;
        }

        public MarketDataProviderRegistration build() {
            return new MarketDataProviderRegistration(id, providers);
        }
    }
}
