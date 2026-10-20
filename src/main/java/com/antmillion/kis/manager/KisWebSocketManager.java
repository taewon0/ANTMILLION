package com.antmillion.kis.manager;

import com.antmillion.kis.dto.KisWebSocketTransactionPriceRequest;
import com.antmillion.kis.handler.KisWebSocketHandler;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.stock.service.TradingEngineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 참조의 희망 상태와 연결별 전송 상태를 분리하고 전용 단일 스레드에서 복원합니다. */
@Slf4j
@Service
public class KisWebSocketManager implements SmartLifecycle, EnvironmentAware {
    private final KisApiService kisApiService;
    private final SimpMessagingTemplate messagingTemplate;
    private final TradingEngineService tradingEngineService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object state = new Object();
    private final Map<String, Subscription> desired = new LinkedHashMap<>();
    private final Map<String, Subscription> sent = new LinkedHashMap<>();
    private Environment environment;
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

    public KisWebSocketManager(KisApiService kisApiService, SimpMessagingTemplate messagingTemplate,
            TradingEngineService tradingEngineService) {
        this.kisApiService = kisApiService;
        this.messagingTemplate = messagingTemplate;
        this.tradingEngineService = tradingEngineService;
    }

    @Override
    public void setEnvironment(Environment environment) { this.environment = environment; }

    @Override
    public void start() {
        synchronized (state) {
            if (running || (environment != null && environment.acceptsProfiles(Profiles.of("dev-mock")))) return;
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

    private static String key(String stockCode, String trId) { return trId + ":" + stockCode; }

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
            synchronized (state) {
                if (!running || generation != startedGeneration) return;
                if (desired.isEmpty()) {
                    current = session;
                    session = null;
                    sent.clear();
                    retryAt = 0;
                    retryDelay = 1000;
                } else {
                    current = null;
                }
            }
            if (current != null) close(current);
            synchronized (state) {
                if (!running || generation != startedGeneration || desired.isEmpty()
                        || (retryAt != 0 && nowMillis() < retryAt)) return;
                current = session;
            }
            if (current == null) current = connect(startedGeneration);
            if (current == null) return;
            // 연결 대기 중 바뀐 참조를 다시 읽어 이전 스냅샷을 재전송하지 않습니다.
            Map<String, Subscription> additions;
            Map<String, Subscription> removals;
            String currentApprovalKey;
            synchronized (state) {
                if (!running || generation != startedGeneration || session != current) return;
                additions = new LinkedHashMap<>(desired);
                additions.keySet().removeAll(sent.keySet());
                removals = new LinkedHashMap<>(sent);
                removals.keySet().removeAll(desired.keySet());
                currentApprovalKey = approvalKey;
            }
            apply(current, startedGeneration, currentApprovalKey, removals, false);
            apply(current, startedGeneration, currentApprovalKey, additions, true);
            synchronized (state) {
                if (!running || generation != startedGeneration || session != current) return;
                retryDelay = 1000;
                retryAt = 0;
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
            synchronized (state) {
                if (!running || generation != startedGeneration || session != current) return;
                if (subscribing ? desired.get(entry.getKey()) != entry.getValue()
                        : desired.containsKey(entry.getKey())) continue;
            }
            // 네트워크 전송 중에도 구독/해제 참조를 갱신할 수 있습니다.
            sendSubscription(current, entry.getValue(), subscribing ? "1" : "2", key);
            synchronized (state) {
                if (!running || generation != startedGeneration || session != current) return;
                // 이미 전송 중이던 프레임은 기록해 다음 조회에서 해제/재획득으로 보정합니다.
                if (subscribing) sent.put(entry.getKey(), entry.getValue());
                else sent.remove(entry.getKey());
            }
        }
    }

    private WebSocketSession connect(long startedGeneration) throws Exception {
        long attempt;
        synchronized (state) {
            if (!running || generation != startedGeneration || desired.isEmpty()) return null;
            activeAttempt = attempt = ++attemptSequence;
        }
        ListenableFuture<WebSocketSession> future = null;
        WebSocketSession opened = null;
        try {
            String key = requestApprovalKey();
            synchronized (state) {
                if (!running || generation != startedGeneration || activeAttempt != attempt || desired.isEmpty()) return null;
            }
            future = handshake(new KisWebSocketHandler(this, key, messagingTemplate, objectMapper,
                    tradingEngineService, attempt));
            synchronized (state) {
                if (!running || generation != startedGeneration || activeAttempt != attempt) return null;
                connecting = future;
            }
            opened = future.get(handshakeTimeoutMillis(), TimeUnit.MILLISECONDS);
            synchronized (state) {
                if (!running || generation != startedGeneration || activeAttempt != attempt
                        || desired.isEmpty() || !opened.isOpen()) return null;
                session = opened;
                approvalKey = key;
                sent.clear();
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
            if (running && activeAttempt == attempt && !desired.isEmpty()) {
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
            sent.clear();
            delayRetry();
        }
        close(lost);
    }

    public boolean isCurrentSession(WebSocketSession source) {
        synchronized (state) { return running && session == source; }
    }

    private void failed(long startedGeneration) {
        WebSocketSession failed;
        synchronized (state) {
            if (!running || generation != startedGeneration) return;
            failed = session;
            session = null;
            sent.clear();
            if (!desired.isEmpty() && (retryAt == 0 || nowMillis() >= retryAt)) delayRetry();
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
            sent.clear();
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
    protected long handshakeTimeoutMillis() { return 5000; }
    protected String requestApprovalKey() { return kisApiService.getKisApprovalKey(); }

    protected ListenableFuture<WebSocketSession> handshake(KisWebSocketHandler handler) {
        StandardWebSocketClient client = new StandardWebSocketClient();
        synchronized (state) {
            if (!running) throw new IllegalStateException("KIS recovery is stopped");
            client.setTaskExecutor(handshakeExecutor);
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put("org.apache.tomcat.websocket.textBufferSize", 65536);
        properties.put("org.apache.tomcat.websocket.binaryBufferSize", 65536);
        client.setUserProperties(properties);
        return client.doHandshake(handler, "ws://ops.koreainvestment.com:21000");
    }

    private static class Subscription {
        private final String stockCode;
        private final String trId;
        private Subscription(String stockCode, String trId) { this.stockCode = stockCode; this.trId = trId; }
    }
}
