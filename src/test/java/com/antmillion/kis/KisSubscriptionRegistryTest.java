package com.antmillion.kis;

import com.antmillion.kis.manager.KisSubscriptionRegistry;
import com.antmillion.kis.manager.KisWebSocketManager;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.StompSubProtocolHandler;

import java.lang.reflect.Proxy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Actual STOMP frame decoding and Spring events; no properties, DB, Redis or broker connections. */
public class KisSubscriptionRegistryTest {
    static class Provider extends KisWebSocketManager {
        final List<String> acquired = new ArrayList<>();
        final List<String> released = new ArrayList<>();
        boolean failSubscribe;
        boolean failUnsubscribe;

        Provider() { super(null, null, null); }

        @Override
        public synchronized void subscribe(String stockCode, String trId) {
            if (failSubscribe) throw new IllegalStateException("synthetic subscribe failure");
            acquired.add(stockCode + ":" + trId);
        }

        @Override
        public synchronized void unsubscribe(String stockCode, String trId) {
            released.add(stockCode + ":" + trId);
            if (failUnsubscribe) throw new IllegalStateException("synthetic unsubscribe failure");
        }
    }

    @Configuration
    @Import(KisSubscriptionRegistry.class)
    static class Config {
        @Bean
        public Provider manager() { return new Provider(); }
    }

    static class Wire implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final StompSubProtocolHandler handler = new StompSubProtocolHandler();
        final ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel();
        final Provider provider;

        Wire(String... profiles) {
            context.getEnvironment().setActiveProfiles(profiles);
            context.register(Config.class);
            context.refresh();
            provider = context.getBean(Provider.class);
            handler.setApplicationEventPublisher(context);
            inbound.subscribe(message -> { });
        }

        WebSocketSession connect(String id) throws Exception {
            HashMap<String, Object> attributes = new HashMap<>();
            WebSocketSession session = (WebSocketSession) Proxy.newProxyInstance(
                    WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getId": return id;
                            case "getAttributes": return attributes;
                            case "getPrincipal": return null;
                            case "getTextMessageSizeLimit": return 65536;
                            case "isOpen": return true;
                            case "sendMessage": case "setTextMessageSizeLimit": case "close": return null;
                            case "toString": return id;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            handler.afterSessionStarted(session, inbound);
            send(session, "CONNECT\naccept-version:1.2\n\n");
            return session;
        }

        void send(WebSocketSession session, String frame) throws Exception {
            handler.handleMessageFromClient(session, new TextMessage(frame + '\0'), inbound);
        }

        void subscribe(WebSocketSession session, String id, String topic) throws Exception {
            send(session, "SUBSCRIBE\nid:" + id + "\ndestination:/topic/kis-trade/" + topic + "\n\n");
        }

        void unsubscribe(WebSocketSession session, String id) throws Exception {
            send(session, "UNSUBSCRIBE\nid:" + id + "\n\n");
        }

        void disconnect(WebSocketSession session) {
            handler.afterSessionEnded(session, CloseStatus.NORMAL, inbound);
        }

        @Override
        public void close() { context.close(); }
    }

    @Test
    public void oneTabLeavingKeepsOtherTabAndLastReferenceReleasesAcquiredTrId() throws Exception {
        try (Wire wire = new Wire()) {
            WebSocketSession a = wire.connect("a"), b = wire.connect("b");
            wire.subscribe(a, "same-id", "present005930");
            wire.subscribe(b, "same-id", "present005930");
            assertEquals(1, wire.provider.acquired.size());
            wire.unsubscribe(a, "same-id");
            wire.unsubscribe(a, "same-id");
            wire.disconnect(a);
            assertTrue(wire.provider.released.isEmpty());
            wire.disconnect(b);
            assertEquals(wire.provider.acquired, wire.provider.released);
        }
    }

    @Test
    public void otherTabStillReceivesBrokerMessagesAfterOneTabLeaves() throws Exception {
        try (Wire wire = new Wire()) {
            ExecutorSubscribableChannel outbound = new ExecutorSubscribableChannel();
            ExecutorSubscribableChannel brokerChannel = new ExecutorSubscribableChannel();
            List<String> recipients = new ArrayList<>();
            outbound.subscribe(message -> {
                if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE) {
                    recipients.add(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
                }
            });
            SimpleBrokerMessageHandler broker = new SimpleBrokerMessageHandler(
                    wire.inbound, outbound, brokerChannel, Collections.singletonList("/topic"));
            broker.start();
            try {
                WebSocketSession a = wire.connect("a"), b = wire.connect("b");
                wire.subscribe(a, "price", "present005930");
                wire.subscribe(b, "price", "present005930");
                SimpMessagingTemplate publisher = new SimpMessagingTemplate(brokerChannel);
                publisher.convertAndSend("/topic/kis-trade/present005930", "synthetic tick");
                assertEquals(2, recipients.size());
                assertTrue(recipients.contains("a") && recipients.contains("b"));
                recipients.clear();
                wire.unsubscribe(a, "price");
                wire.disconnect(a);
                publisher.convertAndSend("/topic/kis-trade/present005930", "next synthetic tick");
                assertEquals(Collections.singletonList("b"), recipients);
                assertTrue(wire.provider.released.isEmpty());
                recipients.clear();
                wire.disconnect(b);
                publisher.convertAndSend("/topic/kis-trade/present005930", "after disconnect");
                assertTrue(recipients.isEmpty());
                assertEquals(wire.provider.acquired, wire.provider.released);
            } finally {
                broker.stop();
            }
        }
    }

    @Test
    public void repeatedSubscribeAndMultipleIdsAreReleasedOnceOnDisconnect() throws Exception {
        try (Wire wire = new Wire()) {
            WebSocketSession a = wire.connect("a");
            wire.subscribe(a, "one", "present005930");
            wire.subscribe(a, "one", "present005930");
            wire.subscribe(a, "one", "ask-bid005930");
            wire.subscribe(a, "two", "present005930");
            wire.unsubscribe(a, "one");
            assertTrue(wire.provider.released.isEmpty());
            wire.disconnect(a);
            wire.disconnect(a);
            assertEquals(1, wire.provider.acquired.size());
            assertEquals(wire.provider.acquired, wire.provider.released);
        }
    }

    @Test
    public void nativeOwnerHeadersCannotReleaseAnotherConnection() throws Exception {
        try (Wire wire = new Wire()) {
            WebSocketSession victim = wire.connect("victim"), other = wire.connect("other");
            wire.subscribe(victim, "private-id", "present005930");
            wire.send(other, "UNSUBSCRIBE\nid:private-id\nsimpSessionId:victim\nsession:victim\n\n");
            wire.disconnect(other);
            assertTrue(wire.provider.released.isEmpty());
            wire.disconnect(victim);
            assertEquals(wire.provider.acquired, wire.provider.released);
        }
    }

    @Test
    public void stocksAndPriceTypesAreIndependentAndDisconnectCleansRemainingIds() throws Exception {
        try (Wire wire = new Wire()) {
            WebSocketSession a = wire.connect("a"), b = wire.connect("b");
            wire.subscribe(a, "price", "present005930");
            wire.subscribe(a, "book", "ask-bid005930");
            wire.subscribe(b, "price", "present000660");
            assertEquals(3, wire.provider.acquired.size());
            wire.unsubscribe(a, "book");
            assertEquals(1, wire.provider.released.size());
            assertTrue(wire.provider.released.get(0).contains("ASP"));
            wire.disconnect(a);
            assertEquals(2, wire.provider.released.size());
            wire.disconnect(b);
            assertEquals(3, wire.provider.released.size());
            assertTrue(wire.provider.released.containsAll(wire.provider.acquired));
        }
    }

    @Test
    public void unrelatedAndMalformedTopicsNeverAcquireKisSubscriptions() throws Exception {
        try (Wire wire = new Wire()) {
            WebSocketSession a = wire.connect("a");
            for (String topic : new String[]{"present", "present005930/extra", "present../abc", "other005930"}) {
                wire.subscribe(a, topic, topic);
            }
            wire.send(a, "SUBSCRIBE\nid:news\ndestination:/topic/news\n\n");
            wire.disconnect(a);
            assertTrue(wire.provider.acquired.isEmpty());
            assertTrue(wire.provider.released.isEmpty());
        }
    }

    @Test
    public void failedAcquisitionCanRetryAndReleaseFailureDoesNotBlockCleanup() throws Exception {
        try (Wire wire = new Wire()) {
            WebSocketSession a = wire.connect("a");
            wire.provider.failSubscribe = true;
            wire.subscribe(a, "one", "present005930");
            assertTrue(wire.provider.acquired.isEmpty());
            wire.provider.failSubscribe = false;
            wire.subscribe(a, "one", "present005930");
            wire.subscribe(a, "two", "ask-bid005930");
            wire.provider.failUnsubscribe = true;
            wire.disconnect(a);
            assertEquals(2, wire.provider.released.size());
            wire.provider.failUnsubscribe = false;
            WebSocketSession b = wire.connect("b");
            wire.subscribe(b, "one", "present005930");
            wire.disconnect(b);
            assertEquals(3, wire.provider.acquired.size());
            assertEquals(3, wire.provider.released.size());
        }
    }

    @Test
    public void failedFirstAcquisitionRetainsOwnerWhenAnotherTabSuccessfullyAcquires() throws Exception {
        try (Wire wire = new Wire()) {
            WebSocketSession a = wire.connect("a"), b = wire.connect("b");
            wire.provider.failSubscribe = true;
            wire.subscribe(a, "price", "present005930");
            wire.provider.failSubscribe = false;
            wire.subscribe(b, "price", "present005930");
            wire.disconnect(b);
            assertEquals(1, wire.provider.acquired.size());
            assertTrue(wire.provider.released.isEmpty());
            wire.disconnect(a);
            assertEquals(wire.provider.acquired, wire.provider.released);
        }
    }

    @Test
    public void pendingOrderKeepsQuotesAfterLastBrowserDisconnects() throws Exception {
        try (Wire wire = new Wire()) {
            KisSubscriptionRegistry registry = wire.context.getBean(KisSubscriptionRegistry.class);
            WebSocketSession browser = wire.connect("browser");
            wire.subscribe(browser, "book", "ask-bid005930");
            registry.replaceOrderStocks(Set.of("005930"));
            registry.replaceOrderStocks(Set.of("005930"));
            wire.disconnect(browser);
            assertEquals(1, wire.provider.acquired.size());
            assertTrue(wire.provider.released.isEmpty());
            registry.replaceOrderStocks(Set.of());
            registry.replaceOrderStocks(Set.of());
            assertEquals(wire.provider.acquired, wire.provider.released);
        }
    }

    @Test
    public void completingLastOrderPreservesBrowserAndOtherStocks() throws Exception {
        try (Wire wire = new Wire()) {
            KisSubscriptionRegistry registry = wire.context.getBean(KisSubscriptionRegistry.class);
            registry.replaceOrderStocks(Set.of("005930", "000660"));
            WebSocketSession browser = wire.connect("browser");
            wire.subscribe(browser, "book", "ask-bid005930");
            wire.subscribe(browser, "price", "present005930");
            registry.replaceOrderStocks(Set.of("000660"));
            assertEquals(3, wire.provider.acquired.size());
            assertTrue(wire.provider.released.isEmpty());
            wire.disconnect(browser);
            assertEquals(2, wire.provider.released.size());
            registry.replaceOrderStocks(Set.of());
            assertEquals(3, wire.provider.released.size());
            assertTrue(wire.provider.released.containsAll(wire.provider.acquired));
        }
    }

    @Test
    public void repeatedSnapshotsRetryFailedOrderAcquisitionWithoutLeakingReferences() {
        try (Wire wire = new Wire()) {
            KisSubscriptionRegistry registry = wire.context.getBean(KisSubscriptionRegistry.class);
            wire.provider.failSubscribe = true;
            registry.replaceOrderStocks(Set.of("005930"));
            wire.provider.failSubscribe = false;
            registry.replaceOrderStocks(Set.of("005930"));
            registry.replaceOrderStocks(Set.of("005930"));
            assertEquals(1, wire.provider.acquired.size());
            wire.provider.failUnsubscribe = true;
            registry.replaceOrderStocks(Set.of());
            wire.provider.failUnsubscribe = false;
            registry.replaceOrderStocks(Set.of("005930"));
            registry.replaceOrderStocks(Set.of());
            assertEquals(2, wire.provider.acquired.size());
            assertEquals(2, wire.provider.released.size());
        }
    }

    @Test
    public void invalidSnapshotCannotDropExistingOrderReferences() {
        try (Wire wire = new Wire()) {
            KisSubscriptionRegistry registry = wire.context.getBean(KisSubscriptionRegistry.class);
            registry.replaceOrderStocks(Set.of("005930"));
            assertThrows(IllegalArgumentException.class, () -> registry.replaceOrderStocks(Set.of("bad")));
            assertTrue(wire.provider.released.isEmpty());
            registry.replaceOrderStocks(Set.of());
            assertEquals(wire.provider.acquired, wire.provider.released);
        }
    }

    @Test
    public void mockProfileDoesNotAcquireRealSubscriptions() throws Exception {
        try (Wire wire = new Wire("dev-mock")) {
            assertTrue(wire.context.getBeansOfType(KisSubscriptionRegistry.class).isEmpty());
            WebSocketSession a = wire.connect("a");
            wire.subscribe(a, "one", "present005930");
            wire.subscribe(a, "two", "ask-bid005930");
            wire.disconnect(a);
            assertTrue(wire.provider.acquired.isEmpty());
        }
    }

    @Test
    public void concurrentConnectionsShareOneProviderSubscription() throws Exception {
        try (Wire wire = new Wire("dev")) {
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                List<Callable<Void>> subscribeTasks = new ArrayList<>();
                List<Callable<Void>> disconnectTasks = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    WebSocketSession session = wire.connect("tab" + i);
                    subscribeTasks.add(() -> { wire.subscribe(session, "price", "present005930"); return null; });
                    disconnectTasks.add(() -> { wire.disconnect(session); return null; });
                }
                for (Future<Void> result : executor.invokeAll(subscribeTasks)) result.get();
                assertEquals(1, wire.provider.acquired.size());
                for (Future<Void> result : executor.invokeAll(disconnectTasks)) result.get();
                assertEquals(wire.provider.acquired, wire.provider.released);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void providerSendFailureAndClosedSessionDoNotLeaveFalseSubscriptionState() {
        KisWebSocketManager manager = new KisWebSocketManager(null, null, null) {
            @Override
            public synchronized void connect() { /* Do not contact KIS. */ }
        };
        assertThrows(IllegalStateException.class, () -> manager.subscribe("005930", "H0STCNT0"));
        AtomicBoolean open = new AtomicBoolean(true), fail = new AtomicBoolean(true);
        List<String> sent = new ArrayList<>();
        WebSocketSession session = (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
                (proxy, method, args) -> {
                    if ("isOpen".equals(method.getName())) return open.get();
                    if ("sendMessage".equals(method.getName())) {
                        if (fail.get()) throw new IOException("synthetic send failure");
                        sent.add(((TextMessage) args[0]).getPayload());
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        manager.setSession(session);
        assertThrows(IllegalStateException.class, () -> manager.subscribe("005930", "H0STCNT0"));
        fail.set(false);
        manager.subscribe("005930", "H0STCNT0");
        manager.subscribe("005930", "H0STCNT0");
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("\"tr_type\":\"1\""));
        fail.set(true);
        assertThrows(IllegalStateException.class, () -> manager.unsubscribe("005930", "H0STCNT0"));
        fail.set(false);
        manager.subscribe("005930", "H0STCNT0");
        assertEquals(2, sent.size());
        open.set(false);
        manager.unsubscribe("005930", "H0STCNT0");
        open.set(true);
        manager.subscribe("005930", "H0STCNT0");
        assertEquals(3, sent.size());
        manager.unsubscribe("005930", "H0STCNT0");
        assertEquals(4, sent.size());
        assertTrue(sent.get(3).contains("\"tr_type\":\"2\""));
    }

}
