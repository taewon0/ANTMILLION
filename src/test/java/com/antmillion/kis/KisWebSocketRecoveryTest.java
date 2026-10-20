package com.antmillion.kis;

import com.antmillion.kis.handler.KisWebSocketHandler;
import com.antmillion.kis.manager.KisWebSocketManager;
import com.antmillion.kis.manager.KisSubscriptionRegistry;
import com.antmillion.stock.service.TradingEngineService;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** 실제 manager·handler에 연결/시각/타이머만 대입하며 DB·설정·KIS·Redis를 로드하지 않습니다. */
public class KisWebSocketRecoveryTest {
    static class Timer extends ThreadPoolTaskScheduler {
        private static final long serialVersionUID = 1L;
        Runnable task;
        boolean closed;
        @Override public void initialize() { }
        @Override public void shutdown() { closed = true; }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay) {
            assertEquals(250, delay);
            this.task = task;
            return new ScheduledFuture<Object>() {
                boolean cancelled;
                public long getDelay(TimeUnit unit) { return 0; }
                public int compareTo(Delayed other) { return 0; }
                public boolean cancel(boolean interrupt) { cancelled = true; return true; }
                public boolean isCancelled() { return cancelled; }
                public boolean isDone() { return cancelled; }
                public Object get() { return null; }
                public Object get(long timeout, TimeUnit unit) { return null; }
            };
        }
    }

    static class Connection {
        final AtomicBoolean open = new AtomicBoolean(true);
        final List<String> messages = new CopyOnWriteArrayList<>();
        final CountDownLatch sent = new CountDownLatch(1);
        CountDownLatch sendStarted;
        CountDownLatch allowSend;
        boolean failSend;
        final WebSocketSession session = (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isOpen": return open.get();
                        case "close": open.set(false); return null;
                        case "sendMessage":
                            if (sendStarted != null) sendStarted.countDown();
                            if (allowSend != null && !allowSend.await(2, TimeUnit.SECONDS)) throw new IOException("send gate timed out");
                            if (failSend) throw new IOException("synthetic send failure");
                            messages.add(((TextMessage) args[0]).getPayload());
                            sent.countDown();
                            return null;
                        case "toString": return "synthetic-session";
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    static class Manager extends KisWebSocketManager implements AutoCloseable {
        Timer timer;
        long now;
        int approvals;
        int attempts;
        boolean failApproval;
        boolean failHandshake;
        boolean pending;
        KisWebSocketHandler handler;
        SettableListenableFuture<WebSocketSession> future;
        Connection connection;
        CountDownLatch connecting = new CountDownLatch(1);
        Manager() { this(null, null); }
        Manager(SimpMessagingTemplate template, TradingEngineService engine) { super(null, template, engine); }
        @Override protected ThreadPoolTaskScheduler createScheduler() { return timer = new Timer(); }
        @Override protected long nowMillis() { return now; }
        @Override protected long handshakeTimeoutMillis() { return 2000; }
        @Override protected String requestApprovalKey() {
            approvals++;
            if (failApproval) throw new IllegalStateException("synthetic approval failure");
            return "synthetic-approval";
        }
        @Override protected ListenableFuture<WebSocketSession> handshake(KisWebSocketHandler handler) {
            attempts++;
            this.handler = handler;
            future = new SettableListenableFuture<>();
            if (failHandshake) future.setException(new IOException("synthetic handshake failure"));
            else if (!pending) establish(new Connection());
            connecting.countDown();
            return future;
        }
        void establish(Connection value) {
            connection = value;
            try { handler.afterConnectionEstablished(value.session); }
            catch (Exception e) { throw new AssertionError(e); }
            future.set(value.session);
        }
        void tick() { timer.task.run(); }
        void advance(long millis) { now += millis; tick(); }
        @Override public void close() { stop(); }
    }

    @Test
    public void asyncIntentsAreDeduplicatedAndIdleLifecycleDoesNotConnect() {
        try (Manager manager = new Manager()) {
            manager.subscribe("005930", "H0STCNT0");
            manager.start();
            manager.start();
            assertEquals(0, manager.attempts);
            manager.subscribe("005930", "H0STCNT0");
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            assertEquals(1, manager.attempts);
            assertEquals(2, manager.connection.messages.size());
            manager.tick();
            assertEquals(2, manager.connection.messages.size());
            manager.unsubscribe("005930", "H0STCNT0");
            manager.tick();
            assertTrue(manager.connection.messages.get(2).contains("\"tr_type\":\"2\""));
            manager.unsubscribe("005930", "H0STASP0");
            Connection old = manager.connection;
            manager.tick();
            assertFalse(old.open.get());
            manager.advance(60000);
            assertEquals(1, manager.attempts);
        }
    }

    @Test
    public void everyCloseCodeRestoresOnlyLiveSubscriptionsAndIgnoresOldCallbacks() {
        for (CloseStatus status : List.of(CloseStatus.NORMAL, CloseStatus.GOING_AWAY,
                CloseStatus.TOO_BIG_TO_PROCESS, CloseStatus.SERVER_ERROR)) {
            try (Manager manager = new Manager()) {
                manager.start();
                manager.subscribe("005930", "H0STCNT0");
                manager.subscribe("005930", "H0STASP0");
                manager.tick();
                Connection old = manager.connection;
                KisWebSocketHandler oldHandler = manager.handler;
                oldHandler.afterConnectionClosed(old.session, status);
                manager.unsubscribe("005930", "H0STCNT0");
                manager.subscribe("000660", "H0STASP0");
                manager.tick();
                assertEquals(1, manager.attempts);
                manager.advance(1000);
                assertEquals(2, manager.attempts);
                Connection restored = manager.connection;
                assertEquals(2, restored.messages.size());
                assertTrue(restored.messages.stream().noneMatch(message -> message.contains("H0STCNT0")));
                oldHandler.afterConnectionClosed(old.session, status);
                oldHandler.handleTransportError(old.session, new IOException("old event"));
                manager.advance(60000);
                assertEquals(2, manager.attempts);
                assertTrue(restored.open.get());
            }
        }
    }

    @Test
    public void connectionAndApprovalFailuresBackOffExponentiallyWithThirtySecondCap() {
        try (Manager manager = new Manager()) {
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.failApproval = true;
            manager.tick();
            assertEquals(1, manager.approvals);
            manager.advance(999);
            assertEquals(1, manager.approvals);
            manager.failApproval = false;
            manager.failHandshake = true;
            manager.advance(1);
            assertEquals(1, manager.attempts);
            for (long delay : new long[]{2000, 4000, 8000, 16000, 30000, 30000}) {
                int before = manager.attempts;
                manager.advance(delay - 1);
                assertEquals(before, manager.attempts);
                manager.advance(1);
                assertEquals(before + 1, manager.attempts);
            }
            manager.failHandshake = false;
            manager.advance(30000);
            assertEquals(1, manager.connection.messages.size());
            manager.handler.handleTransportError(manager.connection.session, new IOException("new outage"));
            int before = manager.attempts;
            manager.advance(999);
            assertEquals(before, manager.attempts);
            manager.advance(1);
            assertEquals(before + 1, manager.attempts);
        }
    }

    @Test
    public void sendFailureDiscardsConnectionAndReplaysOnNextConnection() {
        try (Manager manager = new Manager()) {
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            Connection old = manager.connection;
            old.failSend = true;
            manager.subscribe("000660", "H0STASP0");
            manager.tick();
            assertFalse(old.open.get());
            manager.unsubscribe("005930", "H0STASP0");
            manager.advance(1000);
            assertEquals(1, manager.connection.messages.size());
            assertTrue(manager.connection.messages.get(0).contains("000660"));
        }
    }

    @Test
    public void unsubscribeFailureCannotRestoreReleasedIntent() {
        try (Manager manager = new Manager()) {
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.subscribe("000660", "H0STASP0");
            manager.tick();
            manager.connection.failSend = true;
            manager.unsubscribe("005930", "H0STASP0");
            manager.tick();
            manager.advance(1000);
            assertEquals(1, manager.connection.messages.size());
            assertTrue(manager.connection.messages.get(0).contains("000660"));
        }
    }

    @Test
    public void handshakeTimeoutClosesLateSuccessWithoutRevivingCancelledIntent() throws Exception {
        try (Manager manager = new Manager() {
            @Override protected long handshakeTimeoutMillis() { return 10; }
        }) {
            manager.pending = true;
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            assertTrue(manager.future.isCancelled());
            manager.unsubscribe("005930", "H0STASP0");
            Connection late = new Connection();
            manager.establish(late);
            assertFalse(late.open.get());
            manager.advance(60000);
            assertEquals(1, manager.attempts);
            assertTrue(late.messages.isEmpty());
        }
    }

    @Test
    public void cancellationDuringHandshakeIsAppliedBeforeReplay() throws Exception {
        try (Manager manager = new Manager()) {
            manager.pending = true;
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.subscribe("000660", "H0STASP0");
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> result = worker.submit(manager::tick);
                assertTrue(manager.connecting.await(2, TimeUnit.SECONDS));
                manager.unsubscribe("005930", "H0STASP0");
                manager.establish(new Connection());
                result.get(2, TimeUnit.SECONDS);
                assertEquals(1, manager.connection.messages.size());
                assertTrue(manager.connection.messages.get(0).contains("000660"));
            } finally { worker.shutdownNow(); }
        }
    }

    @Test
    public void stopDuringHandshakeAndLateOldWorkerCannotTouchRestartedSession() throws Exception {
        try (Manager manager = new Manager()) {
            manager.pending = true;
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> result = worker.submit(manager::tick);
                assertTrue(manager.connecting.await(2, TimeUnit.SECONDS));
                Timer oldTimer = manager.timer;
                KisWebSocketHandler oldHandler = manager.handler;
                manager.stop();
                result.get(2, TimeUnit.SECONDS);
                assertTrue(oldTimer.closed);
                assertTrue(manager.future.isCancelled());
                manager.pending = false;
                manager.start();
                manager.tick();
                Connection restored = manager.connection;
                Connection late = new Connection();
                oldHandler.afterConnectionEstablished(late.session);
                assertFalse(late.open.get());
                oldTimer.task.run();
                assertTrue(manager.isCurrentSession(restored.session));
                assertEquals(1, restored.messages.size());
                manager.stop();
                manager.stop();
                assertFalse(restored.open.get());
            } finally { worker.shutdownNow(); }
        }
    }

    @Test
    public void heartbeatUsesCurrentSessionAndFailureTriggersRecovery() throws Exception {
        try (Manager manager = new Manager()) {
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            Connection old = manager.connection;
            String heartbeat = "{\"tr_id\":\"PINGPONG\"}";
            manager.handler.handleMessage(old.session, new TextMessage(heartbeat));
            assertEquals(heartbeat, old.messages.get(1));
            old.failSend = true;
            manager.handler.handleMessage(old.session, new TextMessage(heartbeat));
            assertFalse(old.open.get());
            manager.advance(1000);
            manager.echoHeartbeat(old.session, heartbeat);
            assertEquals(1, manager.connection.messages.size());
        }
    }

    @Test
    public void devMockLifecycleNeverCreatesRecoveryWorker() {
        try (Manager manager = new Manager()) {
            manager.setEnvironment(new MockEnvironment().withProperty("spring.profiles.active", "dev-mock"));
            manager.start();
            assertFalse(manager.isRunning());
            assertNull(manager.timer);
            assertEquals(0, manager.approvals);
        }
    }

    @Test
    public void refsRemainResponsiveDuringSendAndInFlightFrameIsCorrected() throws Exception {
        try (Manager manager = new Manager()) {
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            Connection connection = manager.connection;
            connection.sendStarted = new CountDownLatch(1);
            connection.allowSend = new CountDownLatch(1);
            manager.subscribe("000660", "H0STASP0");
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> result = worker.submit(manager::tick);
                assertTrue(connection.sendStarted.await(2, TimeUnit.SECONDS));
                manager.unsubscribe("000660", "H0STASP0");
                connection.allowSend.countDown();
                result.get(2, TimeUnit.SECONDS);
                manager.tick();
                assertEquals(3, connection.messages.size());
                assertTrue(connection.messages.get(2).contains("\"tr_type\":\"2\""));
                assertTrue(connection.messages.get(2).contains("000660"));
            } finally { connection.allowSend.countDown(); worker.shutdownNow(); }
        }
    }

    @Test
    public void closedSocketWithoutCallbackIsDetectedAndRestored() {
        try (Manager manager = new Manager()) {
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            manager.connection.open.set(false);
            manager.tick();
            assertEquals(1, manager.attempts);
            manager.advance(1000);
            assertEquals(2, manager.attempts);
            assertEquals(1, manager.connection.messages.size());
        }
    }

    @Test
    public void actualSpringLifecycleSchedulesAndClosesWorkerWithoutExternalCalls() throws Exception {
        Manager manager = new Manager() {
            @Override protected ThreadPoolTaskScheduler createScheduler() { return new ThreadPoolTaskScheduler(); }
        };
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("manager", Manager.class, () -> manager);
            context.refresh();
            assertTrue(manager.isRunning());
            assertEquals(0, manager.attempts);
            manager.subscribe("005930", "H0STASP0");
            assertTrue(manager.connecting.await(2, TimeUnit.SECONDS));
            assertTrue(manager.connection.sent.await(2, TimeUnit.SECONDS));
        }
        assertFalse(manager.isRunning());
        assertFalse(manager.connection.open.get());
    }

    @Test
    public void scheduleRegistrationFailureCleansUpAndAllowsRestart() {
        try (Manager manager = new Manager() {
            boolean first = true;
            @Override protected ThreadPoolTaskScheduler createScheduler() {
                if (!first) return super.createScheduler();
                first = false;
                return timer = new Timer() {
                    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay) {
                        throw new IllegalStateException("synthetic registration failure");
                    }
                };
            }
        }) {
            assertThrows(IllegalStateException.class, manager::start);
            assertFalse(manager.isRunning());
            assertTrue(manager.timer.closed);
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            assertEquals(1, manager.connection.messages.size());
        }
    }

    @Test
    public void registrySharesScreenAndOrderIntentsAndRestoresOnlyRemainingOwner() {
        try (Manager manager = new Manager()) {
            KisSubscriptionRegistry registry = new KisSubscriptionRegistry(manager);
            manager.start();
            registry.replaceOrderStocks(Set.of("005930"));
            registry.connected(new SessionConnectEvent(this, frame(StompCommand.CONNECT, "a", null, null)));
            registry.connected(new SessionConnectEvent(this, frame(StompCommand.CONNECT, "b", null, null)));
            registry.subscribed(new SessionSubscribeEvent(this, frame(StompCommand.SUBSCRIBE, "a", "price", "present005930")));
            registry.subscribed(new SessionSubscribeEvent(this, frame(StompCommand.SUBSCRIBE, "b", "book", "ask-bid005930")));
            manager.tick();
            assertEquals(2, manager.connection.messages.size());
            manager.handler.afterConnectionClosed(manager.connection.session, CloseStatus.SERVER_ERROR);
            registry.disconnected(new SessionDisconnectEvent(this, frame(StompCommand.DISCONNECT, "a", null, null), "a", CloseStatus.NORMAL));
            registry.replaceOrderStocks(Set.of());
            manager.advance(1000);
            assertEquals(1, manager.connection.messages.size());
            assertTrue(manager.connection.messages.get(0).contains("ASP0"));
            registry.disconnected(new SessionDisconnectEvent(this, frame(StompCommand.DISCONNECT, "b", null, null), "b", CloseStatus.NORMAL));
            manager.tick();
            assertFalse(manager.connection.open.get());
        }
    }

    private Message<byte[]> frame(StompCommand command, String session, String id, String topic) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setSessionId(session);
        if (id != null) headers.setSubscriptionId(id);
        if (topic != null) headers.setDestination("/topic/kis-trade/" + topic);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
