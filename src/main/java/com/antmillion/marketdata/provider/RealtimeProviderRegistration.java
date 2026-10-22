package com.antmillion.marketdata.provider;

import com.antmillion.marketdata.provider.RealtimeSubscriptionProvider.Kind;

import java.util.Objects;
import java.util.Set;

/** WS Provider의 식별자·구독 구현·지원 시세 종류를 불변 값으로 등록합니다. */
public final class RealtimeProviderRegistration {
    private final String id;
    private final RealtimeSubscriptionProvider provider;
    private final Set<Kind> supportedKinds;

    public RealtimeProviderRegistration(String id, RealtimeSubscriptionProvider provider, Set<Kind> supportedKinds) {
        if (id == null || !id.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Realtime Provider id must use lowercase letters, digits or hyphens");
        }
        this.id = id;
        this.provider = Objects.requireNonNull(provider, "provider");
        this.supportedKinds = Set.copyOf(supportedKinds);
    }

    public String getId() { return id; }

    public RealtimeSubscriptionProvider getProvider() { return provider; }

    public Set<Kind> getSupportedKinds() { return supportedKinds; }
}
