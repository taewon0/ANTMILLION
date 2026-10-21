package com.antmillion.kis;

import com.antmillion.kis.handler.KisWebSocketHandler;
import com.antmillion.kis.manager.KisWebSocketManager;
import com.antmillion.kis.manager.KisSubscriptionRegistry;
import com.antmillion.stock.service.TradingEngineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
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
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.Arrays;
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
import java.util.function.Consumer;

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
        Consumer<String> onSend;
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
                            if (onSend != null) onSend.accept(((TextMessage) args[0]).getPayload());
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
        boolean autoAcknowledge = true;
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
            KisWebSocketHandler receivingHandler = handler;
            value.onSend = payload -> {
                if (!autoAcknowledge) return;
                try {
                    JsonNode request = new ObjectMapper().readTree(payload);
                    JsonNode input = request.path("body").path("input");
                    if (!input.has("tr_key")) return;
                    response(receivingHandler, value, input.path("tr_key").asText(), input.path("tr_id").asText(),
                            "0", "2".equals(request.path("header").path("tr_type").asText())
                                    ? "UNSUBSCRIBE SUCCESS" : "SUBSCRIBE SUCCESS");
                } catch (Exception e) { throw new AssertionError(e); }
            };
            try { handler.afterConnectionEstablished(value.session); }
            catch (Exception e) { throw new AssertionError(e); }
            future.set(value.session);
        }
        void tick() { timer.task.run(); }
        void advance(long millis) { now += millis; tick(); }
        void response(String stock, String trId, String result, String message) {
            response(handler, connection, stock, trId, result, message);
        }
        static void response(KisWebSocketHandler handler, Connection connection, String stock, String trId,
                String result, String message) {
            receive(handler, connection, "{\"header\":{\"tr_id\":\"" + trId + "\",\"tr_key\":\"" + stock
                    + "\"},\"body\":{\"rt_cd\":\"" + result + "\",\"msg_cd\":\"SYNTHETIC\",\"msg1\":\"" + message + "\"}}");
        }
        static void receive(KisWebSocketHandler handler, Connection connection, String payload) {
            try { handler.handleMessage(connection.session, new TextMessage(payload)); }
            catch (Exception e) { throw new AssertionError(e); }
        }
        @Override public void close() { stop(); }
    }

    @Test
    public void silentOpenSocketExpiresAtNinetySecondsAndRestoresOnlyRemainingRefs() throws Exception {
        try (Manager manager = new Manager()) {
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.subscribe("000660", "H0STASP0");
            manager.tick();
            Connection old = manager.connection;
            KisWebSocketHandler oldHandler = manager.handler;
            manager.unsubscribe("000660", "H0STASP0");
            manager.tick();
            manager.advance(89999);
            assertTrue(old.open.get());
            // 송신 성공만으로 상대편 수신이 살아 있다고 판단하지 않습니다.
            manager.echoHeartbeat(old.session, "{\"header\":{\"tr_id\":\"PINGPONG\"}}");
            manager.advance(1);
            assertFalse("An open socket with no inbound traffic must be replaced", old.open.get());
            assertEquals(1, manager.attempts);
            manager.advance(999);
            assertEquals(1, manager.attempts);
            manager.advance(1);
            assertEquals(2, manager.attempts);
            assertEquals(1, manager.connection.messages.size());
            assertTrue(manager.connection.messages.get(0).contains("005930"));
            Connection restored = manager.connection;
            manager.advance(89999);
            Manager.receive(oldHandler, old, "{\"header\":{\"tr_id\":\"PINGPONG\"}}");
            Manager.response(oldHandler, old, "005930", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            oldHandler.handleMessage(old.session, new PongMessage(ByteBuffer.allocate(0)));
            oldHandler.afterConnectionClosed(old.session, CloseStatus.NORMAL);
            manager.advance(1);
            assertFalse("Old callbacks must not extend the new connection's receive deadline", restored.open.get());
        }
    }

    @Test
    public void heartbeatRealtimeAndPongKeepConnectionAliveWithoutRequiringTicks() throws Exception {
        ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
        channel.subscribe(message -> { });
        for (String kind : List.of("heartbeat", "realtime", "pong")) {
            try (Manager manager = new Manager(new SimpMessagingTemplate(channel), null)) {
                manager.start();
                manager.subscribe("005930", "H0STCNT0");
                manager.tick();
                Connection original = manager.connection;
                for (int i = 0; i < 4; i++) {
                    manager.advance(45000);
                    if ("heartbeat".equals(kind)) {
                        Manager.receive(manager.handler, original, "{\"header\":{\"tr_id\":\"PINGPONG\"}}");
                    } else if ("pong".equals(kind)) {
                        manager.handler.handleMessage(original.session, new PongMessage(ByteBuffer.allocate(0)));
                    } else {
                        String[] fields = new String[23];
                        Arrays.fill(fields, "0");
                        fields[0] = "005930";
                        fields[2] = "1000";
                        Manager.receive(manager.handler, original, "0|H0STCNT0|001|" + String.join("^", fields));
                    }
                    assertTrue(kind, original.open.get());
                    assertEquals(kind, 1, manager.attempts);
                }
                manager.advance(89999);
                assertTrue(original.open.get());
                manager.advance(1);
                assertFalse(kind, original.open.get());
            }
        }
    }

    @Test
    public void receiveTimeoutIsConfigurableAndInvalidValuesFailBeforeStartingResources() {
        try (Manager manager = new Manager()) {
            for (String invalid : List.of("0", "-1", "5000", "invalid")) {
                manager.setEnvironment(new MockEnvironment().withProperty("kis.websocket.receive-timeout-millis", invalid));
                try {
                    manager.start();
                    fail("Invalid receive timeout must fail before starting the recovery worker");
                } catch (RuntimeException expected) {
                    assertFalse(manager.isRunning());
                    assertNull(manager.timer);
                    assertEquals(0, manager.approvals);
                }
            }
            manager.setEnvironment(new MockEnvironment().withProperty("kis.websocket.receive-timeout-millis", "180000"));
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            manager.advance(179999);
            assertTrue(manager.connection.open.get());
            manager.advance(1);
            assertFalse(manager.connection.open.get());
        }
    }

    @Test
    public void silenceRecoveryDoesNotResumeRejectedOrAckExhaustedRefs() {
        for (boolean rejected : List.of(true, false)) {
            try (Manager manager = new Manager()) {
                manager.autoAcknowledge = false;
                manager.start();
                manager.subscribe("005930", "H0STASP0");
                manager.subscribe("000660", "H0STASP0");
                manager.tick();
                if (rejected) {
                    manager.response("000660", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
                    manager.response("005930", "H0STASP0", "1", "SYNTHETIC REJECTION");
                } else {
                    for (int attempt = 1; attempt <= 3; attempt++) {
                        manager.response("000660", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
                        manager.advance(5000);
                        if (attempt < 3) manager.advance(attempt * 1000L);
                    }
                    manager.autoAcknowledge = true;
                    manager.advance(1000);
                }
                manager.autoAcknowledge = true;
                Connection old = manager.connection;
                manager.advance(90000);
                assertFalse(old.open.get());
                manager.advance(1000);
                assertEquals(1, manager.connection.messages.size());
                assertTrue(manager.connection.messages.get(0).contains("000660"));
                manager.unsubscribe("000660", "H0STASP0");
                manager.tick();
                assertFalse(manager.connection.open.get());
                int before = manager.attempts;
                manager.advance(90000);
                assertEquals("Suspended refs alone must not reconnect", before, manager.attempts);
            }
        }
    }

    @Test
    public void receiveTrafficCannotExtendAckDeadlineOrResetAckRetryBudget() {
        try (Manager manager = new Manager()) {
            manager.autoAcknowledge = false;
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            for (int attempt = 1; attempt <= 3; attempt++) {
                Connection current = manager.connection;
                manager.advance(4999);
                Manager.receive(manager.handler, current, "{\"header\":{\"tr_id\":\"PINGPONG\"}}");
                manager.advance(1);
                assertFalse("Inbound heartbeat cannot replace the pending subscription ACK", current.open.get());
                assertEquals(attempt, manager.attempts);
                if (attempt < 3) manager.advance(attempt * 1000L);
            }
            manager.advance(90000);
            assertEquals(3, manager.attempts);
        }
    }

    @Test
    public void releasingLastRefDuringSilenceBackoffAndStoppingCannotReviveConnection() {
        try (Manager manager = new Manager()) {
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            manager.advance(90000);
            assertFalse(manager.connection.open.get());
            manager.unsubscribe("005930", "H0STASP0");
            manager.advance(90000);
            assertEquals(1, manager.attempts);
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            Connection current = manager.connection;
            Runnable oldWorker = manager.timer.task;
            manager.stop();
            manager.now += 90000;
            oldWorker.run();
            assertFalse(current.open.get());
            assertEquals(2, manager.attempts);
            manager.start();
            manager.tick();
            oldWorker.run();
            assertTrue(manager.connection.open.get());
            assertEquals(3, manager.attempts);
        }
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
            String heartbeat = "{\"header\":{\"tr_id\":\"PINGPONG\"}}";
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

    @Test
    public void pendingSubscriptionWaitsForAckAndOnlyConfirmedStateAvoidsTimeout() {
        try (Manager manager = new Manager()) {
            manager.autoAcknowledge = false;
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            manager.advance(4999);
            assertEquals(1, manager.connection.messages.size());
            assertTrue(manager.connection.open.get());
            manager.response("005930", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            manager.response("005930", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            manager.advance(60000);
            assertEquals(1, manager.attempts);
            assertTrue(manager.connection.open.get());
            assertEquals(1, manager.connection.messages.size());
        }
    }

    @Test
    public void missingAckRetriesTwiceWithOneAndTwoSecondDelaysThenSuspends() {
        try (Manager manager = new Manager()) {
            manager.autoAcknowledge = false;
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            for (long delay : new long[]{1000, 2000}) {
                Connection old = manager.connection;
                KisWebSocketHandler oldHandler = manager.handler;
                manager.advance(5000);
                assertFalse(old.open.get());
                Manager.response(oldHandler, old, "005930", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
                int before = manager.attempts;
                manager.advance(delay - 1);
                assertEquals(before, manager.attempts);
                manager.advance(1);
                assertEquals(before + 1, manager.attempts);
                assertEquals(1, manager.connection.messages.size());
            }
            manager.advance(5000);
            assertFalse(manager.connection.open.get());
            manager.subscribe("005930", "H0STASP0");
            manager.advance(60000);
            assertEquals(3, manager.attempts);
            manager.unsubscribe("005930", "H0STASP0");
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            assertEquals(4, manager.attempts);
            manager.response("005930", "H0STASP0", "1", "SYNTHETIC REJECTION");
            manager.tick();
            manager.stop();
            manager.autoAcknowledge = true;
            manager.start();
            manager.tick();
            assertEquals(5, manager.attempts);
            manager.advance(60000);
            assertTrue(manager.connection.open.get());
        }
    }

    @Test
    public void rejectionIsSuspendedAcrossReconnectButOtherTrAndStockRemainLive() {
        try (Manager manager = new Manager()) {
            manager.autoAcknowledge = false;
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.subscribe("005930", "H0STCNT0");
            manager.subscribe("000660", "H0STASP0");
            manager.tick();
            manager.response("005930", "H0STASP0", "1", "SYNTHETIC REJECTION");
            manager.response("005930", "H0STCNT0", "0", "SUBSCRIBE SUCCESS");
            manager.response("000660", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            manager.advance(60000);
            assertEquals(1, manager.attempts);
            assertEquals(3, manager.connection.messages.size());
            manager.handler.afterConnectionClosed(manager.connection.session, CloseStatus.SERVER_ERROR);
            manager.autoAcknowledge = true;
            manager.advance(1000);
            assertEquals(2, manager.connection.messages.size());
            assertTrue(manager.connection.messages.stream().noneMatch(message ->
                    message.contains("005930") && message.contains("H0STASP0")));
            manager.unsubscribe("005930", "H0STASP0");
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            assertEquals(3, manager.connection.messages.size());
        }
    }

    @Test
    public void cancellationDuringPendingAckWaitsThenUnsubscribesWithoutRevival() {
        try (Manager manager = new Manager()) {
            manager.autoAcknowledge = false;
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.subscribe("000660", "H0STASP0");
            manager.tick();
            manager.response("000660", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            manager.unsubscribe("005930", "H0STASP0");
            manager.tick();
            assertEquals(2, manager.connection.messages.size());
            manager.response("005930", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            manager.tick();
            assertEquals(3, manager.connection.messages.size());
            assertTrue(manager.connection.messages.get(2).contains("\"tr_type\":\"2\""));
            // 직전 구독의 중복 ACK는 해제 승인이 될 수 없습니다.
            manager.response("005930", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            manager.response("005930", "H0STASP0", "0", "UNSUBSCRIBE SUCCESS");
            manager.advance(60000);
            assertEquals(1, manager.attempts);
            assertEquals(3, manager.connection.messages.size());
        }
    }

    @Test
    public void rejectedOrTimedOutUnsubscribeReplacesConnectionAndRestoresOnlyRemainingRefs() {
        for (boolean rejected : new boolean[]{true, false}) {
            try (Manager manager = new Manager()) {
                manager.start();
                manager.subscribe("005930", "H0STASP0");
                manager.subscribe("000660", "H0STASP0");
                manager.tick();
                manager.autoAcknowledge = false;
                manager.unsubscribe("005930", "H0STASP0");
                manager.tick();
                Connection old = manager.connection;
                if (rejected) manager.response("005930", "H0STASP0", "1", "SYNTHETIC REJECTION");
                manager.advance(rejected ? 0 : 5000);
                assertFalse(old.open.get());
                manager.autoAcknowledge = true;
                manager.advance(1000);
                assertEquals(2, manager.attempts);
                assertEquals(1, manager.connection.messages.size());
                assertTrue(manager.connection.messages.get(0).contains("000660"));
                manager.advance(60000);
                assertEquals(2, manager.attempts);
            }
        }
    }

    @Test
    public void reacquisitionUsesNewConnectionToDisambiguateDuplicateOldAck() {
        try (Manager manager = new Manager()) {
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.subscribe("000660", "H0STASP0");
            manager.tick();
            Connection old = manager.connection;
            KisWebSocketHandler oldHandler = manager.handler;
            manager.autoAcknowledge = false;
            manager.unsubscribe("005930", "H0STASP0");
            manager.tick();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            assertEquals(3, old.messages.size());
            manager.response("005930", "H0STASP0", "0", "UNSUBSCRIBE SUCCESS");
            manager.tick();
            assertFalse(old.open.get());
            manager.autoAcknowledge = true;
            manager.advance(1000);
            Manager.response(oldHandler, old, "005930", "H0STASP0", "1", "SYNTHETIC REJECTION");
            manager.advance(60000);
            assertEquals(2, manager.attempts);
            assertTrue(manager.connection.open.get());
            assertEquals(2, manager.connection.messages.size());
        }
    }

    @Test
    public void malformedMismatchedAndUnknownResponsesCannotConfirmPendingRequest() {
        try (Manager manager = new Manager()) {
            manager.autoAcknowledge = false;
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.tick();
            for (String raw : new String[]{"{broken", "{}", "{\"header\":{\"tr_id\":\"H0STASP0\"}}",
                    "{\"header\":{\"tr_id\":\"H0STASP0\",\"tr_key\":\"005930\"},\"body\":{\"rt_cd\":0,\"msg1\":\"SUBSCRIBE SUCCESS\"}}"}) {
                Manager.receive(manager.handler, manager.connection, raw);
            }
            manager.response("000660", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            manager.response("005930", "H0STCNT0", "0", "SUBSCRIBE SUCCESS");
            manager.response("005930", "UNKNOWN", "0", "SUBSCRIBE SUCCESS");
            manager.response("005930", "H0STASP0", "0", "UNSUBSCRIBE SUCCESS");
            manager.response("005930", "H0STASP0", "0", "UNKNOWN SUCCESS");
            // 본문에 PINGPONG이 있어도 heartbeat로 분류하지 않습니다.
            manager.response("005930", "H0STASP0", "0", "PINGPONG");
            assertEquals(1, manager.connection.messages.size());
            manager.advance(5000);
            assertFalse(manager.connection.open.get());
        }
    }

    @Test
    public void successfulAckResetsTimeoutBudgetAndTimeoutDoesNotSuspendHealthyRefs() {
        try (Manager manager = new Manager()) {
            manager.autoAcknowledge = false;
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.subscribe("000660", "H0STASP0");
            manager.tick();
            manager.response("000660", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            manager.advance(5000);
            manager.advance(1000);
            manager.response("000660", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            manager.response("005930", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            manager.tick();
            manager.handler.afterConnectionClosed(manager.connection.session, CloseStatus.SERVER_ERROR);
            manager.advance(1000);
            manager.response("000660", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            manager.advance(5000);
            int before = manager.attempts;
            manager.advance(1000);
            assertEquals(before + 1, manager.attempts);
            assertEquals(2, manager.connection.messages.size());
        }
    }

    @Test
    public void timeoutExhaustionSuspendsOnlyFailingRefAndDoesNotRetryOnSharedAcquisition() {
        try (Manager manager = new Manager()) {
            manager.autoAcknowledge = false;
            KisSubscriptionRegistry registry = new KisSubscriptionRegistry(manager);
            manager.start();
            registry.replaceOrderStocks(Set.of("005930"));
            registry.connected(new SessionConnectEvent(this, frame(StompCommand.CONNECT, "screen", null, null)));
            registry.subscribed(new SessionSubscribeEvent(this, frame(StompCommand.SUBSCRIBE, "screen", "book", "ask-bid005930")));
            manager.subscribe("000660", "H0STASP0");
            manager.tick();
            for (int attempt = 1; attempt <= 3; attempt++) {
                manager.response("000660", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
                manager.advance(5000);
                assertFalse(manager.connection.open.get());
                if (attempt != 3) manager.advance(attempt * 1000L);
            }
            registry.replaceOrderStocks(Set.of("005930"));
            registry.disconnected(new SessionDisconnectEvent(this, frame(StompCommand.DISCONNECT, "screen", null, null),
                    "screen", CloseStatus.NORMAL));
            manager.autoAcknowledge = true;
            manager.advance(1000);
            assertEquals(1, manager.connection.messages.size());
            assertTrue(manager.connection.messages.get(0).contains("000660"));
            manager.advance(60000);
            assertEquals(4, manager.attempts);
            registry.replaceOrderStocks(Set.of());
            registry.replaceOrderStocks(Set.of("005930"));
            manager.tick();
            assertEquals(2, manager.connection.messages.size());
        }
    }

    @Test
    public void releasedPendingRefCannotSuspendReacquiredRefOnRejection() {
        try (Manager manager = new Manager()) {
            manager.autoAcknowledge = false;
            manager.start();
            manager.subscribe("005930", "H0STASP0");
            manager.subscribe("000660", "H0STASP0");
            manager.tick();
            manager.response("000660", "H0STASP0", "0", "SUBSCRIBE SUCCESS");
            manager.unsubscribe("005930", "H0STASP0");
            manager.subscribe("005930", "H0STASP0");
            manager.response("005930", "H0STASP0", "1", "SYNTHETIC REJECTION");
            manager.tick();
            assertFalse(manager.connection.open.get());
            manager.autoAcknowledge = true;
            manager.advance(1000);
            manager.advance(60000);
            assertEquals(2, manager.attempts);
            assertTrue(manager.connection.open.get());
            assertEquals(2, manager.connection.messages.size());
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
