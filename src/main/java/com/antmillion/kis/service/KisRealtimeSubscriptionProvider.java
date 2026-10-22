package com.antmillion.kis.service;

import com.antmillion.kis.manager.KisWebSocketManager;
import com.antmillion.marketdata.provider.RealtimeSubscriptionProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** TR 선택·ACK·연결 복원은 기존 KIS Manager에 위임합니다. */
@Service
@Profile("!dev-mock")
@RequiredArgsConstructor
public class KisRealtimeSubscriptionProvider implements RealtimeSubscriptionProvider {
    private final KisWebSocketManager manager;

    @Override
    public void subscribe(String stockCode, Kind kind) {
        manager.subscribeCurrentMarket(stockCode, isCurrentPrice(kind));
    }

    @Override
    public void unsubscribe(String stockCode, Kind kind) {
        manager.unsubscribeCurrentMarket(stockCode, isCurrentPrice(kind));
    }

    private boolean isCurrentPrice(Kind kind) {
        return Objects.requireNonNull(kind, "kind") == Kind.CURRENT_PRICE;
    }
}
