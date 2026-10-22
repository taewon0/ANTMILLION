package com.antmillion.marketdata;

import com.antmillion.kis.service.KisRealtimeSubscriptionProvider;
import com.antmillion.marketdata.provider.RealtimeSubscriptionProvider;
import com.antmillion.marketdata.provider.RealtimeSubscriptionProvider.Kind;
import com.antmillion.marketdata.service.RealtimeSubscriptionRegistry;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;
import org.springframework.web.socket.CloseStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** KIS 상속·설정·DB·외부 연결 없이 공통 계약만으로 소유권 흐름을 검증합니다. */
public class RealtimeSubscriptionProviderTest {
    @Test
    public void alternateProviderSharesBrowserAndOrderOwnershipByStockAndKind() {
        RecordingProvider provider = new RecordingProvider();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RealtimeSubscriptionProvider.class, () -> provider);
            context.register(RealtimeSubscriptionRegistry.class);
            context.refresh();
            RealtimeSubscriptionRegistry registry = context.getBean(RealtimeSubscriptionRegistry.class);
            context.publishEvent(new SessionConnectEvent(this, frame(StompCommand.CONNECT, "a", null, null)));
            context.publishEvent(new SessionConnectEvent(this, frame(StompCommand.CONNECT, "b", null, null)));
            subscribe(context, "a", "price", "present005930");
            subscribe(context, "a", "book", "ask-bid005930");
            subscribe(context, "b", "book", "ask-bid005930");
            subscribe(context, "b", "book", "ask-bid005930");
            registry.replaceOrderStocks(Set.of("005930"));
            registry.replaceOrderStocks(Set.of("005930"));
            assertEquals(List.of("005930:CURRENT_PRICE", "005930:ORDER_BOOK"), provider.acquired);

            context.publishEvent(new SessionDisconnectEvent(this,
                    frame(StompCommand.DISCONNECT, "a", null, null), "a", CloseStatus.NORMAL));
            assertEquals(List.of("005930:CURRENT_PRICE"), provider.released);
            context.publishEvent(new SessionUnsubscribeEvent(this,
                    frame(StompCommand.UNSUBSCRIBE, "b", "book", null)));
            assertEquals(1, provider.released.size());
            registry.replaceOrderStocks(Set.of());
            registry.replaceOrderStocks(Set.of());
            assertEquals(provider.acquired, provider.released);
        }
    }

    @Test
    public void mockProfileNeedsNeitherRealtimeProviderNorKisManager() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("dev-mock");
            context.register(RealtimeSubscriptionRegistry.class, KisRealtimeSubscriptionProvider.class);
            context.refresh();
            assertTrue(context.getBeansOfType(RealtimeSubscriptionRegistry.class).isEmpty());
            assertTrue(context.getBeansOfType(RealtimeSubscriptionProvider.class).isEmpty());
        }
    }

    private void subscribe(AnnotationConfigApplicationContext context, String session, String id, String topic) {
        context.publishEvent(new SessionSubscribeEvent(this,
                frame(StompCommand.SUBSCRIBE, session, id, "/topic/kis-trade/" + topic)));
    }

    private Message<byte[]> frame(StompCommand command, String session, String id, String destination) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setSessionId(session);
        if (id != null) headers.setSubscriptionId(id);
        if (destination != null) headers.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private static class RecordingProvider implements RealtimeSubscriptionProvider {
        private final List<String> acquired = new ArrayList<>();
        private final List<String> released = new ArrayList<>();

        @Override
        public void subscribe(String stockCode, Kind kind) { acquired.add(stockCode + ":" + kind); }

        @Override
        public void unsubscribe(String stockCode, Kind kind) { released.add(stockCode + ":" + kind); }
    }
}
