package com.antmillion.kis.manager;

import com.antmillion.kis.constant.KisWebSocketTrId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 브라우저가 지정한 소유자 대신 서버의 STOMP 세션과 구독 ID로 KIS 구독 소유권을 관리합니다. */
@Slf4j
@Component
@Profile("!dev-mock")
@RequiredArgsConstructor
public class KisSubscriptionRegistry {
    private static final Pattern TOPIC = Pattern.compile("^/topic/kis-trade/(present|ask-bid)([A-Za-z0-9]{6})$");
    private final KisWebSocketManager manager;
    private final Map<String, Map<String, String>> sessions = new HashMap<>();
    private final Map<String, SharedSubscription> subscriptions = new HashMap<>();
    private final Set<String> orderSubscriptions = new HashSet<>();

    @EventListener
    public synchronized void connected(SessionConnectEvent event) {
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        if (sessionId != null) sessions.computeIfAbsent(sessionId, ignored -> new HashMap<>());
    }

    @EventListener
    public synchronized void subscribed(SessionSubscribeEvent event) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, String> owned = sessions.get(headers.getSessionId());
        String id = headers.getSubscriptionId();
        String destination = headers.getDestination();
        if (owned == null || id == null || destination == null) return;
        Matcher topic = TOPIC.matcher(destination);
        if (!topic.matches()) return;
        if (owned.containsKey(id)) {
            if (destination.equals(owned.get(id))) {
                acquire(subscriptions.get(destination));
            } else {
                log.warn("Ignoring reused STOMP subscription ID");
            }
            return;
        }

        SharedSubscription shared = shared(destination, topic.group(1), topic.group(2));
        shared.references++;
        owned.put(id, destination);
        acquire(shared);
    }

    /** 미체결 주문 종목마다 서버 참조 하나를 유지하고 모든 화면의 호가 구독 참조와 공유합니다. */
    public synchronized void replaceOrderStocks(Set<String> stockCodes) {
        Set<String> destinations = new HashSet<>();
        // 기존 참조를 변경하기 전에 조회 결과 전체를 검증합니다.
        for (String stockCode : stockCodes) {
            if (stockCode == null || !stockCode.matches("[A-Za-z0-9]{6}")) {
                throw new IllegalArgumentException("Invalid pending order stock code");
            }
            destinations.add("/topic/kis-trade/ask-bid" + stockCode);
        }
        for (String destination : destinations) {
            String stockCode = destination.substring("/topic/kis-trade/ask-bid".length());
            SharedSubscription shared = shared(destination, "ask-bid", stockCode);
            if (orderSubscriptions.add(destination)) shared.references++;
            // 구독 획득에 실패해도 참조를 유지하고 다음 DB 조회 때 다시 시도합니다.
            acquire(shared);
        }
        Set<String> removed = new HashSet<>(orderSubscriptions);
        removed.removeAll(destinations);
        for (String destination : removed) {
            orderSubscriptions.remove(destination);
            release(destination);
        }
    }

    private SharedSubscription shared(String destination, String kind, String stockCode) {
        return subscriptions.computeIfAbsent(destination, ignored -> new SharedSubscription(stockCode,
                "present".equals(kind) ? KisWebSocketTrId.getCurrentPresentTrId()
                        : KisWebSocketTrId.getCurrentAskBidTrId()));
    }

    private void acquire(SharedSubscription shared) {
        if (shared.requested) return;
        try {
            manager.subscribe(shared.stockCode, shared.trId);
            shared.requested = true;
        } catch (RuntimeException e) {
            // STOMP 구독이 남아 있으므로 다음 획득 재시도를 위해 소유권을 유지합니다.
            log.warn("KIS subscription acquisition failed: {}", e.getClass().getSimpleName());
        }
    }

    @EventListener
    public synchronized void unsubscribed(SessionUnsubscribeEvent event) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, String> owned = sessions.get(headers.getSessionId());
        if (owned != null) release(owned.remove(headers.getSubscriptionId()));
    }

    @EventListener
    public synchronized void disconnected(SessionDisconnectEvent event) {
        Map<String, String> owned = sessions.remove(event.getSessionId());
        if (owned != null) owned.values().forEach(this::release);
    }

    private void release(String destination) {
        if (destination == null) return;
        SharedSubscription shared = subscriptions.get(destination);
        if (shared != null && --shared.references == 0) {
            subscriptions.remove(destination);
            if (!shared.requested) return;
            try {
                // 장 마감 시각이 지나도 구독 획득 당시 선택한 TR로 해제합니다.
                manager.unsubscribe(shared.stockCode, shared.trId);
            } catch (RuntimeException e) {
                log.warn("KIS subscription release failed: {}", e.getClass().getSimpleName());
            }
        }
    }

    private static class SharedSubscription {
        private final String stockCode;
        private final String trId;
        private int references;
        private boolean requested;

        private SharedSubscription(String stockCode, String trId) {
            this.stockCode = stockCode;
            this.trId = trId;
        }
    }
}
