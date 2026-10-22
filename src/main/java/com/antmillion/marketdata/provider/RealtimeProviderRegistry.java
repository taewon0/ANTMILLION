package com.antmillion.marketdata.provider;

import com.antmillion.marketdata.provider.RealtimeSubscriptionProvider.Kind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 화면 현재가와 주문 호가에 필요한 WS 지원을 확인하고 기동 시 선택을 고정합니다. */
public final class RealtimeProviderRegistry {
    private final RealtimeProviderRegistration selected;

    public RealtimeProviderRegistry(String selectedId, List<RealtimeProviderRegistration> registrations) {
        Map<String, RealtimeProviderRegistration> byId = new LinkedHashMap<>();
        for (RealtimeProviderRegistration registration : registrations) {
            Objects.requireNonNull(registration, "registration");
            if (byId.putIfAbsent(registration.getId(), registration) != null) {
                throw new IllegalArgumentException("Duplicate realtime Provider id: " + registration.getId());
            }
        }
        selected = byId.get(selectedId);
        if (selected == null) {
            throw new IllegalArgumentException("marketdata.realtime-provider must identify a registered WS Provider");
        }
        if (!selected.getSupportedKinds().containsAll(Set.of(Kind.CURRENT_PRICE, Kind.ORDER_BOOK))) {
            throw new UnsupportedOperationException("Selected WS Provider must support CURRENT_PRICE and ORDER_BOOK");
        }
    }

    public String getSelectedId() { return selected.getId(); }

    public Set<Kind> getSupportedKinds() { return selected.getSupportedKinds(); }

    public RealtimeSubscriptionProvider getProvider() { return selected.getProvider(); }
}
