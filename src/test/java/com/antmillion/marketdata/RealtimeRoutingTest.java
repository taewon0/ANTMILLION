package com.antmillion.marketdata;

import com.antmillion.kis.config.KisRealtimeProviderConfig;
import com.antmillion.kis.handler.KisWebSocketHandler;
import com.antmillion.kis.manager.KisWebSocketManager;
import com.antmillion.kis.service.KisRealtimeSubscriptionProvider;
import com.antmillion.marketdata.config.RealtimeRoutingConfig;
import com.antmillion.marketdata.provider.RealtimeProviderRegistration;
import com.antmillion.marketdata.provider.RealtimeProviderRegistry;
import com.antmillion.marketdata.provider.RealtimeSubscriptionProvider;
import com.antmillion.marketdata.provider.RealtimeSubscriptionProvider.Kind;
import com.antmillion.marketdata.service.RealtimeSubscriptionRegistry;
import org.junit.Test;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.*;

/** 실제 선택·KIS 등록·수명 관리와 STOMP 소유권을 검증하며 개인 설정/DB/외부 연결은 로드하지 않습니다. */
public class RealtimeRoutingTest {
    private static final Set<Kind> BOTH = Set.of(Kind.CURRENT_PRICE, Kind.ORDER_BOOK);

    @Test
    public void defaultAndExplicitKisSelectActualAdapterAndStartAndStopManager() {
        for (String selected : new String[]{null, "kis"}) {
            TrackingManager manager = new TrackingManager();
            try (AnnotationConfigApplicationContext context = context(selected, null, manager)) {
                context.refresh();
                RealtimeProviderRegistry registry = context.getBean(RealtimeProviderRegistry.class);
                assertEquals("kis", registry.getSelectedId());
                assertEquals(BOTH, registry.getSupportedKinds());
                RealtimeSubscriptionProvider provider = context.getBean(RealtimeSubscriptionProvider.class);
                assertSame(context.getBean(KisRealtimeSubscriptionProvider.class), provider);
                assertTrue(manager.isRunning());
                assertEquals(1, manager.schedulers);
                provider.subscribe("005930", Kind.CURRENT_PRICE);
                provider.subscribe("000660", Kind.ORDER_BOOK);
                provider.unsubscribe("005930", Kind.CURRENT_PRICE);
                provider.unsubscribe("000660", Kind.ORDER_BOOK);
                assertEquals(List.of("+005930:true", "+000660:false", "-005930:true", "-000660:false"), manager.calls);
                assertNoConnections(manager);
            }
            assertFalse(manager.isRunning());
        }
    }

    @Test
    public void alternateSharesBrowserAndOrderReferencesWhileKisCannotStartEvenWithIntent() {
        TrackingManager manager = new TrackingManager();
        manager.subscribe("005930", "H0STASP0");
        RecordingProvider alternate = new RecordingProvider();
        try (AnnotationConfigApplicationContext context = context("alternate", null, manager)) {
            register(context, "alternate", alternate, BOTH);
            context.refresh();
            assertSame(alternate, context.getBean(RealtimeSubscriptionProvider.class));
            assertEquals("alternate", context.getBean(RealtimeProviderRegistry.class).getSelectedId());
            context.publishEvent(new SessionConnectEvent(this, frame(StompCommand.CONNECT, "a", null, null)));
            context.publishEvent(new SessionConnectEvent(this, frame(StompCommand.CONNECT, "b", null, null)));
            subscribe(context, "a", "price", "present005930");
            subscribe(context, "a", "book", "ask-bid005930");
            subscribe(context, "b", "book", "ask-bid005930");
            RealtimeSubscriptionRegistry subscriptions = context.getBean(RealtimeSubscriptionRegistry.class);
            subscriptions.replaceOrderStocks(Set.of("005930"));
            assertEquals(List.of("+005930:CURRENT_PRICE", "+005930:ORDER_BOOK"), alternate.calls);
            disconnect(context, "a");
            disconnect(context, "b");
            assertEquals(3, alternate.calls.size());
            subscriptions.replaceOrderStocks(Set.of());
            assertEquals(List.of("+005930:CURRENT_PRICE", "+005930:ORDER_BOOK",
                    "-005930:CURRENT_PRICE", "-005930:ORDER_BOOK"), alternate.calls);
            // 실행 중 환경 변경·직접 start도 기동 시 선택을 바꾸지 않습니다.
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("changed",
                    Map.of("marketdata.realtime-provider", "kis")));
            manager.start();
            assertEquals("alternate", context.getBean(RealtimeProviderRegistry.class).getSelectedId());
            assertFalse(manager.isRunning());
            assertEquals(0, manager.schedulers);
            assertTrue(manager.calls.isEmpty());
            assertNoConnections(manager);
        }
    }

    @Test
    public void placeholderFileAndEnvironmentOverrideControlBothRoutingAndKisLifecycle() {
        for (String selected : new String[]{null, "kis"}) {
            TrackingManager manager = new TrackingManager();
            try (AnnotationConfigApplicationContext context = context(selected, "alternate", manager)) {
                register(context, "alternate", new RecordingProvider(), BOTH);
                context.refresh();
                String expected = selected == null ? "alternate" : "kis";
                assertEquals(expected, context.getBean(RealtimeProviderRegistry.class).getSelectedId());
                assertEquals("kis".equals(expected), manager.isRunning());
                assertEquals("kis".equals(expected) ? 1 : 0, manager.schedulers);
                assertNoConnections(manager);
            }
        }
    }

    @Test
    public void restSelectionDoesNotChangeDefaultWsSelection() {
        TrackingManager manager = new TrackingManager();
        try (AnnotationConfigApplicationContext context = context(null, null, manager)) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("rest",
                    Map.of("marketdata.provider", "alternate")));
            context.refresh();
            assertEquals("kis", context.getBean(RealtimeProviderRegistry.class).getSelectedId());
            assertTrue(manager.isRunning());
        }
    }

    @Test
    public void invalidOrUnregisteredSelectionFailsBeforeAnyWorkerStarts() {
        for (String selected : List.of("", "unknown", "KIS", "kiwoom", " kis ")) {
            TrackingManager manager = new TrackingManager();
            try (AnnotationConfigApplicationContext context = context(selected, null, manager)) {
                assertThrows(BeansException.class, context::refresh);
                assertEquals(0, manager.schedulers);
                assertFalse(manager.isRunning());
                assertNoConnections(manager);
            }
        }
    }

    @Test
    public void duplicateIdsAndMissingRequiredKindsFailBeforeAnyWorkerStarts() {
        for (Set<Kind> kinds : List.of(Set.<Kind>of(), Set.of(Kind.CURRENT_PRICE), Set.of(Kind.ORDER_BOOK))) {
            TrackingManager manager = new TrackingManager();
            try (AnnotationConfigApplicationContext context = context("partial", null, manager)) {
                register(context, "partial", new RecordingProvider(), kinds);
                BeansException failure = assertThrows(BeansException.class, context::refresh);
                assertTrue(failure.getMostSpecificCause() instanceof UnsupportedOperationException);
                assertEquals(0, manager.schedulers);
            }
        }
        TrackingManager manager = new TrackingManager();
        try (AnnotationConfigApplicationContext context = context("kis", null, manager)) {
            register(context, "kis", new RecordingProvider(), BOTH);
            BeansException failure = assertThrows(BeansException.class, context::refresh);
            assertTrue(failure.getMostSpecificCause().getMessage().contains("Duplicate"));
            assertEquals(0, manager.schedulers);
            assertNoConnections(manager);
        }
    }

    @Test
    public void unselectedIncompleteProviderDoesNotPreventKisStartup() {
        TrackingManager manager = new TrackingManager();
        try (AnnotationConfigApplicationContext context = context("kis", null, manager)) {
            register(context, "partial", new RecordingProvider(), Set.of(Kind.CURRENT_PRICE));
            context.refresh();
            assertTrue(manager.isRunning());
            assertEquals("kis", context.getBean(RealtimeProviderRegistry.class).getSelectedId());
        }
    }

    @Test
    public void registrationValidatesAndCopiesCapabilitiesAndRegistryIgnoresRegistrationOrder() {
        RecordingProvider provider = new RecordingProvider();
        Set<Kind> kinds = new HashSet<>(BOTH);
        RealtimeProviderRegistration registration = new RealtimeProviderRegistration("alternate", provider, kinds);
        kinds.clear();
        assertEquals(BOTH, registration.getSupportedKinds());
        assertThrows(UnsupportedOperationException.class, () -> registration.getSupportedKinds().clear());
        RealtimeProviderRegistration kis = new RealtimeProviderRegistration("kis", new RecordingProvider(), BOTH);
        for (List<RealtimeProviderRegistration> registrations : List.of(List.of(kis, registration), List.of(registration, kis))) {
            assertSame(provider, new RealtimeProviderRegistry("alternate", registrations).getProvider());
        }
        for (String id : new String[]{null, "", "KIS", "bad id"}) {
            assertThrows(IllegalArgumentException.class, () -> new RealtimeProviderRegistration(id, provider, BOTH));
        }
        assertThrows(NullPointerException.class, () -> new RealtimeProviderRegistration("kis", null, BOTH));
        assertThrows(NullPointerException.class, () -> new RealtimeProviderRegistration("kis", provider, null));
    }

    @Test
    public void devMockExcludesRoutingRegistrationAndOwnershipAndBlocksManager() {
        TrackingManager manager = new TrackingManager();
        try (AnnotationConfigApplicationContext context = context("unknown", null, manager, true)) {
            context.refresh();
            assertTrue(context.getBeansOfType(RealtimeProviderRegistry.class).isEmpty());
            assertTrue(context.getBeansOfType(RealtimeProviderRegistration.class).isEmpty());
            assertTrue(context.getBeansOfType(RealtimeSubscriptionProvider.class).isEmpty());
            assertTrue(context.getBeansOfType(RealtimeSubscriptionRegistry.class).isEmpty());
            manager.start();
            assertFalse(manager.isRunning());
            assertEquals(0, manager.schedulers);
            assertNoConnections(manager);
        }
    }

    private AnnotationConfigApplicationContext context(String selected, String localSelected, TrackingManager manager) {
        return context(selected, localSelected, manager, false);
    }

    private AnnotationConfigApplicationContext context(String selected, String localSelected, TrackingManager manager, boolean mock) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (mock) context.getEnvironment().setActiveProfiles("dev-mock");
        context.getEnvironment().getPropertySources().remove("systemProperties");
        context.getEnvironment().getPropertySources().remove("systemEnvironment");
        if (selected != null) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic",
                    Map.of("marketdata.realtime-provider", selected)));
        }
        context.registerBean(PropertySourcesPlaceholderConfigurer.class, () -> {
            PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
            if (localSelected != null) {
                Properties properties = new Properties();
                properties.setProperty("marketdata.realtime-provider", localSelected);
                configurer.setProperties(properties);
            }
            return configurer;
        });
        context.registerBean("manager", TrackingManager.class, () -> manager);
        context.register(RealtimeRoutingConfig.class, KisRealtimeProviderConfig.class,
                KisRealtimeSubscriptionProvider.class, RealtimeSubscriptionRegistry.class);
        return context;
    }

    private void register(AnnotationConfigApplicationContext context, String id, RecordingProvider provider, Set<Kind> kinds) {
        context.registerBean("syntheticRegistration", RealtimeProviderRegistration.class,
                () -> new RealtimeProviderRegistration(id, provider, kinds));
    }

    private void subscribe(AnnotationConfigApplicationContext context, String session, String id, String topic) {
        context.publishEvent(new SessionSubscribeEvent(this,
                frame(StompCommand.SUBSCRIBE, session, id, "/topic/kis-trade/" + topic)));
    }

    private void disconnect(AnnotationConfigApplicationContext context, String session) {
        context.publishEvent(new SessionDisconnectEvent(this,
                frame(StompCommand.DISCONNECT, session, null, null), session, CloseStatus.NORMAL));
    }

    private Message<byte[]> frame(StompCommand command, String session, String id, String destination) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setSessionId(session);
        if (id != null) headers.setSubscriptionId(id);
        if (destination != null) headers.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private void assertNoConnections(TrackingManager manager) {
        assertEquals(0, manager.approvals);
        assertEquals(0, manager.handshakes);
    }

    private static class RecordingProvider implements RealtimeSubscriptionProvider {
        final List<String> calls = new ArrayList<>();
        @Override public void subscribe(String stockCode, Kind kind) { calls.add("+" + stockCode + ":" + kind); }
        @Override public void unsubscribe(String stockCode, Kind kind) { calls.add("-" + stockCode + ":" + kind); }
    }

    private static class TrackingManager extends KisWebSocketManager {
        int schedulers;
        int approvals;
        int handshakes;
        final List<String> calls = new ArrayList<>();
        TrackingManager() { super(null, null); }
        @Override protected ThreadPoolTaskScheduler createScheduler() {
            schedulers++;
            return new ThreadPoolTaskScheduler();
        }
        @Override public void subscribeCurrentMarket(String code, boolean present) { calls.add("+" + code + ":" + present); }
        @Override public void unsubscribeCurrentMarket(String code, boolean present) { calls.add("-" + code + ":" + present); }
        @Override protected String requestApprovalKey() {
            approvals++;
            throw new AssertionError("Unexpected KIS approval request");
        }
        @Override protected ListenableFuture<WebSocketSession> handshake(KisWebSocketHandler handler) {
            handshakes++;
            throw new AssertionError("Unexpected KIS handshake");
        }
    }
}
