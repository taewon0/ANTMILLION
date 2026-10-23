package com.antmillion.kis.manager;

import com.antmillion.kis.dto.KisWebSocketTransactionPriceRequest;
import com.antmillion.kis.dto.KisWebSocketConnectionScope;
import com.antmillion.kis.constant.KisWebSocketTrId;
import com.antmillion.kis.handler.KisWebSocketHandler;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.marketdata.provider.RealtimeMarketDataReceiver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.io.IOException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 희망 참조·ACK 대기·승인 상태를 분리하고 전용 단일 스레드에서 복원합니다. */
@Slf4j
@Service
public class KisWebSocketManager implements SmartLifecycle, EnvironmentAware {
    private static final int MAX_ACK_RETRIES = 2;
    private final KisApiService kisApiService;
    private final RealtimeMarketDataReceiver receiver;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object state = new Object();
    private final Map<String, Subscription> desired = new LinkedHashMap<>();
    private final Map<String, Subscription> confirmed = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    // 응답에는 요청 ID가 없습니다. 같은 연결에서 같은 구독을 다시 보내지 않습니다.
    private final Set<String> attempted = new HashSet<>();
    private Environment environment;
    // Registry와 같은 placeholder로 한 번 주입하며 실행 중 설정 변경은 적용하지 않습니다.
    @Value("${marketdata.realtime-provider:kis}")
    private String realtimeProviderId = "kis";
    private ThreadPoolTaskScheduler scheduler;
    private ThreadPoolTaskExecutor handshakeExecutor;
    private ScheduledFuture<?> task;
    private ListenableFuture<WebSocketSession> connecting;
    private WebSocketSession session;
    private WebSocketSession candidate;
    private String approvalKey;
    private boolean running;
    private long generation;
    private long attemptSequence;
    private long activeAttempt;
    private long retryAt;
    private long retryDelay = 1000;
    private long lastReceivedAt;
    private long receiveTimeoutMillis = 90000;

    public KisWebSocketManager(KisApiService kisApiService, RealtimeMarketDataReceiver receiver) {
        this.kisApiService = kisApiService;
        this.receiver = receiver;
    }

    @Override
    public void setEnvironment(Environment environment) { this.environment = environment; }

    @Override
    public void start() {
        synchronized (state) {
            if (running || !"kis".equals(realtimeProviderId)
                    || (environment != null && environment.acceptsProfiles(Profiles.of("dev-mock")))) return;
            long timeout = environment == null ? 90000
                    : environment.getProperty("kis.websocket.receive-timeout-millis", Long.class, 90000L);
            if (timeout <= acknowledgementTimeoutMillis()) {
                throw new IllegalArgumentException("KIS receive timeout must exceed the ACK timeout");
            }
            receiveTimeoutMillis = timeout;
            scheduler = createScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("kis-recovery-");
            scheduler.setRemoveOnCancelPolicy(true);
            try {
                // 타임아웃을 무시하는 연결 대역/구현도 연결 스레드를 계속 늘릴 수 없습니다.
                handshakeExecutor = new ThreadPoolTaskExecutor();
                handshakeExecutor.setCorePoolSize(1);
                handshakeExecutor.setMaxPoolSize(1);
                handshakeExecutor.setQueueCapacity(0);
                handshakeExecutor.setThreadNamePrefix("kis-handshake-");
                handshakeExecutor.initialize();
                scheduler.initialize();
                running = true;
                long startedGeneration = ++generation;
                task = scheduler.scheduleWithFixedDelay(() -> reconcile(startedGeneration), 250);
                if (task == null) throw new IllegalStateException("KIS recovery task was not scheduled");
            } catch (RuntimeException e) {
                running = false;
                scheduler.shutdown();
                scheduler = null;
                handshakeExecutor.shutdown();
                handshakeExecutor = null;
                throw e;
            }
        }
    }

    /** 호출자는 연결을 기다리지 않고 같은 TR·종목의 희망 상태만 등록합니다. */
    public void subscribe(String stockCode, String trId) {
        synchronized (state) {
            desired.putIfAbsent(key(stockCode, trId), new Subscription(stockCode, trId));
        }
    }

    public void unsubscribe(String stockCode, String trId) {
        synchronized (state) { desired.remove(key(stockCode, trId)); }
    }

    /** 화면·주문 참조는 TR 대신 종목·시세 종류를 유지하고 worker가 현재 앱 정책을 적용합니다. */
    public void subscribeCurrentMarket(String stockCode, boolean present) {
        synchronized (state) {
            if (desired.values().stream().anyMatch(subscription -> subscription.followsMarket
                    && subscription.stockCode.equals(stockCode)
                    && KisWebSocketTrId.isPresentPriceTrId(subscription.trId) == present)) return;
            String trId = present ? KisWebSocketTrId.getPresentTrId(marketTime())
                    : KisWebSocketTrId.getAskBidTrId(marketTime());
            Subscription subscription = new Subscription(stockCode, trId);
            subscription.followsMarket = true;
            desired.putIfAbsent(key(stockCode, trId), subscription);
        }
    }

    public void unsubscribeCurrentMarket(String stockCode, boolean present) {
        synchronized (state) {
            desired.values().removeIf(subscription -> subscription.followsMarket
                    && subscription.stockCode.equals(stockCode)
                    && KisWebSocketTrId.isPresentPriceTrId(subscription.trId) == present);
        }
    }

    /** 전환과 연결 폐기를 함께 반영해 이전 연결의 늦은 ACK·호가가 새 TR에 적용되지 않게 합니다. */
    private WebSocketSession rotateMarketSubscriptions(long startedGeneration) {
        synchronized (state) {
            if (!running || generation != startedGeneration) return null;
            LocalTime time = marketTime();
            Map<String, Subscription> next = new LinkedHashMap<>();
            boolean changed = false;
            for (Subscription subscription : desired.values()) {
                String trId = subscription.trId;
                if (subscription.followsMarket) {
                    trId = KisWebSocketTrId.isPresentPriceTrId(trId)
                            ? KisWebSocketTrId.getPresentTrId(time) : KisWebSocketTrId.getAskBidTrId(time);
                }
                Subscription replacement = subscription;
                if (!trId.equals(subscription.trId)) {
                    replacement = new Subscription(subscription.stockCode, trId);
                    replacement.followsMarket = true;
                    replacement.suspended = subscription.suspended;
                    replacement.timeouts = subscription.timeouts;
                    changed = true;
                }
                next.put(key(replacement.stockCode, replacement.trId), replacement);
            }
            if (!changed) return null;
            desired.clear();
            desired.putAll(next);
            WebSocketSession previous = session;
            session = null;
            clearConnectionState();
            // 기존 장애 대기·거절 보류·ACK 재시도 예산은 장 전환으로 초기화하지 않습니다.
            return previous;
        }
    }

    private static String key(String stockCode, String trId) { return trId + ":" + stockCode; }

    private boolean hasLiveSubscriptions() {
        return desired.values().stream().anyMatch(subscription -> !subscription.suspended);
    }

    private void clearConnectionState() {
        confirmed.clear();
        pending.clear();
        attempted.clear();
    }

    private void reconcile(long startedGeneration) {
        WebSocketSession current;
        synchronized (state) {
            if (!running || generation != startedGeneration) return;
            current = session;
        }
        try {
            if (current != null && !current.isOpen()) {
                connectionLost(current);
                current = null;
            }
            if (current != null && expireAcknowledgements(current, startedGeneration)) return;
            close(rotateMarketSubscriptions(startedGeneration));
            synchronized (state) {
                if (!running || generation != startedGeneration) return;
                if (!hasLiveSubscriptions()) {
                    current = session;
                    session = null;
                    clearConnectionState();
                    retryAt = 0;
                    retryDelay = 1000;
                } else {
                    current = null;
                }
            }
            if (current != null) close(current);
            synchronized (state) {
                if (!running || generation != startedGeneration || !hasLiveSubscriptions()
                        || (retryAt != 0 && nowMillis() < retryAt)) return;
                current = session;
            }
            if (current == null) current = connect(startedGeneration);
            if (current == null) return;
            if (expireAcknowledgements(current, startedGeneration)) return;
            // approval/handshake를 기다리는 동안 전환 시각을 지났을 수도 있습니다.
            close(rotateMarketSubscriptions(startedGeneration));
            if (!isCurrentSession(current)) return;
            if (expireReceive(current, startedGeneration)) return;
            // 연결 대기 중 바뀐 참조를 다시 읽어 이전 스냅샷을 재전송하지 않습니다.
            Map<String, Subscription> additions;
            Map<String, Subscription> removals;
            String currentApprovalKey;
            synchronized (state) {
                if (!running || generation != startedGeneration || session != current) return;
                additions = new LinkedHashMap<>(desired);
                additions.entrySet().removeIf(entry -> entry.getValue().suspended
                        || confirmed.containsKey(entry.getKey()) || pending.containsKey(entry.getKey()));
                removals = new LinkedHashMap<>(confirmed);
                removals.entrySet().removeIf(entry -> desired.get(entry.getKey()) == entry.getValue()
                        || pending.containsKey(entry.getKey()));
                currentApprovalKey = approvalKey;
            }
            apply(current, startedGeneration, currentApprovalKey, removals, false);
            apply(current, startedGeneration, currentApprovalKey, additions, true);
            synchronized (state) {
                if (!running || generation != startedGeneration || session != current) return;
                if (pending.isEmpty()) {
                    retryDelay = 1000;
                    retryAt = 0;
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            failed(startedGeneration);
            log.warn("KIS recovery failed: {}", e.getClass().getSimpleName());
        }
    }

    private void apply(WebSocketSession current, long startedGeneration, String key,
            Map<String, Subscription> changes, boolean subscribing) throws IOException {
        for (Map.Entry<String, Subscription> entry : changes.entrySet()) {
            Pending request;
            boolean reused;
            synchronized (state) {
                if (!running || generation != startedGeneration || session != current) return;
                if (pending.containsKey(entry.getKey())) continue;
                if (subscribing ? desired.get(entry.getKey()) != entry.getValue() || entry.getValue().suspended
                        : desired.get(entry.getKey()) == entry.getValue()) continue;
                reused = subscribing && attempted.contains(entry.getKey());
                request = new Pending(entry.getValue(), subscribing, nowMillis());
                if (!reused) {
                    // sendMessage 안에서 ACK가 들어와도 대기 요청이 먼저 보여야 합니다.
                    pending.put(entry.getKey(), request);
                    if (subscribing) attempted.add(entry.getKey());
                }
            }
            if (reused) {
                reconnect(current, startedGeneration, 1000);
                return;
            }
            // 네트워크 전송 중에도 구독/해제 참조를 갱신할 수 있습니다.
            sendSubscription(current, entry.getValue(), subscribing ? "1" : "2", key);
        }
    }

    /** 현재 연결의 대기 요청에만 응답을 적용합니다. 원문/approval/복호화 key는 기록하지 않습니다. */
    public void acknowledge(WebSocketSession source, String stockCode, String trId,
            boolean success, boolean unsubscribing) {
        synchronized (state) {
            if (!running || session != source) return;
            String key = key(stockCode, trId);
            Pending request = pending.get(key);
            if (request == null || request.failed || (success && request.subscribing == unsubscribing)) return;
            pending.remove(key);
            if (success) {
                if (request.subscribing) {
                    confirmed.put(key, request.subscription);
                    request.subscription.timeouts = 0;
                } else {
                    confirmed.remove(key);
                }
                if (pending.isEmpty()) {
                    retryDelay = 1000;
                    retryAt = 0;
                }
            } else if (request.subscribing) {
                request.subscription.suspended = true;
                log.warn("KIS subscription suspended after rejection: TR={}, stock={}", trId, stockCode);
            } else {
                // 해제 거절은 원격 참조가 남을 수 있으므로 worker가 연결을 교체합니다.
                request.failed = true;
                pending.put(key, request);
            }
        }
    }

    private boolean expireAcknowledgements(WebSocketSession current, long startedGeneration) {
        long delay = 0;
        synchronized (state) {
            if (!running || generation != startedGeneration || session != current) return true;
            long now = nowMillis();
            for (Pending request : pending.values()) {
                if (!request.failed && now - request.startedAt < acknowledgementTimeoutMillis()) continue;
                delay = Math.max(delay, 1000);
                Subscription subscription = request.subscription;
                if (request.subscribing && desired.get(key(subscription.stockCode, subscription.trId)) == subscription) {
                    if (++subscription.timeouts > MAX_ACK_RETRIES) {
                        subscription.suspended = true;
                        log.warn("KIS subscription suspended after ACK timeout: TR={}, stock={}",
                                subscription.trId, subscription.stockCode);
                    } else {
                        delay = Math.max(delay, subscription.timeouts * 1000L);
                    }
                }
            }
            if (delay != 0) {
                // 만료 판정과 연결 폐기를 원자적으로 처리해 뒤늦은 ACK가 예산을 되돌리지 못하게 합니다.
                session = null;
                clearConnectionState();
                retryAt = now + delay;
            }
        }
        if (delay == 0) return false;
        // 동일 연결 재송신은 늦은 ACK를 새 요청으로 오인할 수 있어 새 연결에서 복원합니다.
        close(current);
        return true;
    }

    private void reconnect(WebSocketSession current, long startedGeneration, long delay) {
        synchronized (state) {
            if (!running || generation != startedGeneration || session != current) return;
            session = null;
            clearConnectionState();
            retryAt = nowMillis() + delay;
        }
        close(current);
    }

    /** 시세가 없어도 PINGPONG 등 수신이 계속되면 연결을 유지합니다. 송신은 수신 시각을 갱신하지 않습니다. */
    private boolean expireReceive(WebSocketSession current, long startedGeneration) {
        synchronized (state) {
            if (!running || generation != startedGeneration || session != current) return true;
            if (!hasLiveSubscriptions() || nowMillis() - lastReceivedAt < receiveTimeoutMillis) return false;
            // 만료 확인과 연결 폐기를 함께 처리하여 늦은 메시지가 새 연결의 시각을 바꾸지 못하게 합니다.
            session = null;
            clearConnectionState();
            delayRetry();
        }
        close(current);
        log.warn("KIS WebSocket receive timeout; reconnect scheduled");
        return true;
    }

    private WebSocketSession connect(long startedGeneration) throws Exception {
        long attempt;
        synchronized (state) {
            if (!running || generation != startedGeneration || !hasLiveSubscriptions()) return null;
            activeAttempt = attempt = ++attemptSequence;
        }
        ListenableFuture<WebSocketSession> future = null;
        WebSocketSession opened = null;
        try {
            KisWebSocketConnectionScope scope = connectionScope();
            String key = requestApprovalKey(scope);
            synchronized (state) {
                if (!running || generation != startedGeneration || activeAttempt != attempt || !hasLiveSubscriptions()) return null;
            }
            future = handshake(new KisWebSocketHandler(this, objectMapper, receiver, attempt), scope.getWebSocketUrl());
            synchronized (state) {
                if (!running || generation != startedGeneration || activeAttempt != attempt) return null;
                connecting = future;
            }
            opened = future.get(handshakeTimeoutMillis(), TimeUnit.MILLISECONDS);
            synchronized (state) {
                if (!running || generation != startedGeneration || activeAttempt != attempt
                        || !hasLiveSubscriptions() || !opened.isOpen()) return null;
                session = opened;
                approvalKey = key;
                clearConnectionState();
                lastReceivedAt = nowMillis();
                candidate = null;
                activeAttempt = 0;
                return opened;
            }
        } finally {
            WebSocketSession abandoned = null;
            synchronized (state) {
                if (activeAttempt == attempt) {
                    activeAttempt = 0;
                    abandoned = candidate;
                    candidate = null;
                }
                if (connecting == future) connecting = null;
                if (opened != null && session != opened) abandoned = opened;
            }
            if (future != null && !future.isDone()) future.cancel(true);
            close(abandoned);
        }
    }

    /** 시간 초과·종료 후 늦게 성립한 연결도 닫습니다. */
    public void connectionEstablished(WebSocketSession opened, long attempt) {
        synchronized (state) {
            if (running && activeAttempt == attempt && hasLiveSubscriptions()) {
                candidate = opened;
                return;
            }
        }
        close(opened);
    }

    public void connectionLost(WebSocketSession lost) {
        synchronized (state) {
            if (candidate == lost) {
                candidate = null;
                activeAttempt = 0;
                if (connecting != null) connecting.cancel(true);
            } else if (session == lost) {
                session = null;
            } else {
                return;
            }
            clearConnectionState();
            delayRetry();
        }
        close(lost);
    }

    public boolean isCurrentSession(WebSocketSession source) {
        synchronized (state) { return running && session == source; }
    }

    /** 메시지 파싱·DB 체결 전에 수신을 기록하며 이전 연결의 메시지는 무시합니다. */
    public boolean messageReceived(WebSocketSession source) {
        synchronized (state) {
            if (!running || source == null || (session != source && candidate != source)) return false;
            if (session == source) lastReceivedAt = nowMillis();
            return true;
        }
    }

    private void failed(long startedGeneration) {
        WebSocketSession failed;
        synchronized (state) {
            if (!running || generation != startedGeneration) return;
            failed = session;
            session = null;
            clearConnectionState();
            if (hasLiveSubscriptions() && (retryAt == 0 || nowMillis() >= retryAt)) delayRetry();
        }
        close(failed);
    }

    private void delayRetry() {
        retryAt = nowMillis() + retryDelay;
        retryDelay = Math.min(retryDelay * 2, 30000);
    }

    public void echoHeartbeat(WebSocketSession source, String payload) {
        synchronized (state) {
            if (session != source && candidate != source) return;
        }
        try {
            send(source, new TextMessage(payload));
        } catch (IOException e) {
            connectionLost(source);
        }
    }

    private void sendSubscription(WebSocketSession target, Subscription subscription, String type, String key) throws IOException {
        KisWebSocketTransactionPriceRequest request = KisWebSocketTransactionPriceRequest.builder()
                .header(KisWebSocketTransactionPriceRequest.Header.builder().approvalKey(key)
                        .custtype("P").trType(type).contentType("utf-8").build())
                .body(KisWebSocketTransactionPriceRequest.Body.builder()
                        .input(KisWebSocketTransactionPriceRequest.Body.Input.builder()
                                .trId(subscription.trId).trKey(subscription.stockCode).build()).build()).build();
        send(target, new TextMessage(objectMapper.writeValueAsString(request)));
    }

    // JSR-356의 동시 전송 제한에 따라 PINGPONG과 구독 전송도 같은 세션에서 직렬화합니다.
    private void send(WebSocketSession target, TextMessage message) throws IOException {
        synchronized (target) { target.sendMessage(message); }
    }

    private void close(WebSocketSession closing) {
        if (closing == null) return;
        try { closing.close(); }
        catch (IOException e) { log.warn("KIS session close failed: {}", e.getClass().getSimpleName()); }
    }

    @Override
    public void stop() {
        ThreadPoolTaskScheduler stopping;
        ThreadPoolTaskExecutor stoppingHandshake;
        WebSocketSession closed;
        WebSocketSession pending;
        synchronized (state) {
            running = false;
            ++generation;
            activeAttempt = 0;
            if (task != null) task.cancel(true);
            task = null;
            if (connecting != null) connecting.cancel(true);
            connecting = null;
            stopping = scheduler;
            scheduler = null;
            stoppingHandshake = handshakeExecutor;
            handshakeExecutor = null;
            closed = session;
            session = null;
            pending = candidate;
            candidate = null;
            clearConnectionState();
            for (Subscription subscription : desired.values()) {
                subscription.suspended = false;
                subscription.timeouts = 0;
            }
            retryAt = 0;
            retryDelay = 1000;
        }
        if (stopping != null) stopping.shutdown();
        if (stoppingHandshake != null) stoppingHandshake.shutdown();
        close(closed);
        close(pending);
    }

    @Override
    public boolean isRunning() { synchronized (state) { return running; } }

    @Override
    public int getPhase() { return 0; }

    protected ThreadPoolTaskScheduler createScheduler() { return new ThreadPoolTaskScheduler(); }
    protected long nowMillis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
    protected LocalTime marketTime() { return LocalTime.now(ZoneId.of("Asia/Seoul")); }
    protected long handshakeTimeoutMillis() { return 5000; }
    protected long acknowledgementTimeoutMillis() { return 5000; }
    protected KisWebSocketConnectionScope connectionScope() { return kisApiService.webSocketConnectionScope(); }
    protected String requestApprovalKey(KisWebSocketConnectionScope scope) { return kisApiService.getKisApprovalKey(scope); }

    protected WebSocketClient createWebSocketClient() {
        StandardWebSocketClient client = new StandardWebSocketClient();
        synchronized (state) {
            if (!running) throw new IllegalStateException("KIS recovery is stopped");
            client.setTaskExecutor(handshakeExecutor);
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put("org.apache.tomcat.websocket.textBufferSize", 65536);
        properties.put("org.apache.tomcat.websocket.binaryBufferSize", 65536);
        client.setUserProperties(properties);
        return client;
    }

    protected ListenableFuture<WebSocketSession> handshake(KisWebSocketHandler handler, String webSocketUrl) {
        return createWebSocketClient().doHandshake(handler, webSocketUrl);
    }

    private static class Subscription {
        private final String stockCode;
        private final String trId;
        private boolean followsMarket;
        private boolean suspended;
        private int timeouts;
        private Subscription(String stockCode, String trId) { this.stockCode = stockCode; this.trId = trId; }
    }

    private static class Pending {
        private final Subscription subscription;
        private final boolean subscribing;
        private final long startedAt;
        private boolean failed;
        private Pending(Subscription subscription, boolean subscribing, long startedAt) {
            this.subscription = subscription;
            this.subscribing = subscribing;
            this.startedAt = startedAt;
        }
    }
}
