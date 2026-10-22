package com.antmillion.marketdata.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 설정된 Provider를 시작 시 확정합니다. 조회 실패 시 다른 Provider로 전환하지 않습니다. */
public final class MarketDataProviderRegistry {
    private final MarketDataProviderRegistration selected;

    public MarketDataProviderRegistry(String selectedId, List<MarketDataProviderRegistration> registrations) {
        Map<String, MarketDataProviderRegistration> byId = new LinkedHashMap<>();
        for (MarketDataProviderRegistration registration : registrations) {
            Objects.requireNonNull(registration, "registration");
            if (byId.putIfAbsent(registration.getId(), registration) != null) {
                throw new IllegalArgumentException("Duplicate market data Provider id: " + registration.getId());
            }
        }
        selected = byId.get(selectedId);
        if (selected == null) {
            throw new IllegalArgumentException("marketdata.provider must identify a registered REST Provider");
        }
    }

    public String getSelectedId() {
        return selected.getId();
    }

    public Set<Class<?>> getSupportedContracts() {
        return selected.getSupportedContracts();
    }

    public <T> T getProvider(Class<T> contract) {
        return selected.getProvider(contract);
    }
}
