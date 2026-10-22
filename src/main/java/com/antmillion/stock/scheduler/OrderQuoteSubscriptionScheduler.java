package com.antmillion.stock.scheduler;

import com.antmillion.marketdata.service.RealtimeSubscriptionRegistry;
import com.antmillion.stock.mapper.StockOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/** 기동 시 구독을 복원하고 브라우저 세션과 독립적으로 커밋된 주문 상태를 반영합니다. */
@Slf4j
@Component
@Profile("!dev-mock")
public class OrderQuoteSubscriptionScheduler implements SmartLifecycle {
    private final StockOrderMapper orders;
    private final RealtimeSubscriptionRegistry registry;
    private final TransactionTemplate readTransaction;
    private final Object applyMonitor = new Object();
    private ThreadPoolTaskScheduler scheduler;
    private ScheduledFuture<?> future;
    private volatile boolean running;
    private volatile long generation;

    public OrderQuoteSubscriptionScheduler(StockOrderMapper orders, RealtimeSubscriptionRegistry registry,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.registry = registry;
        readTransaction = new TransactionTemplate(transactionManager);
        readTransaction.setReadOnly(true);
        readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public synchronized void start() {
        if (running) return;
        scheduler = createScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("order-quotes-");
        scheduler.setRemoveOnCancelPolicy(true);
        try {
            scheduler.initialize();
            running = true;
            long startedGeneration = ++generation;
            // 이 오버로드는 즉시 한 번 실행한 뒤 각 처리 완료 후 1초마다 실행합니다.
            future = scheduler.scheduleWithFixedDelay(() -> refresh(startedGeneration), 1000);
            if (future == null) throw new IllegalStateException("Order quote task was not scheduled");
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    private void refresh(long startedGeneration) {
        if (!running) return;
        try {
            List<String> stockCodes = readTransaction.execute(status -> orders.selectPendingQuoteStockCodes());
            synchronized (applyMonitor) {
                // 종료되거나 교체된 작업 스레드는 종료 후 참조를 다시 복원할 수 없습니다.
                if (running && generation == startedGeneration) {
                    registry.replaceOrderStocks(new HashSet<>(stockCodes));
                }
            }
        } catch (RuntimeException e) {
            // DB 조회 실패를 빈 결과로 처리하지 않고 정상 조회가 성공할 때까지 기존 참조를 유지합니다.
            log.warn("Order quote reconciliation failed: {}", e.getClass().getSimpleName());
        }
    }

    @Override
    public void stop() {
        ThreadPoolTaskScheduler stopping;
        long stoppedGeneration;
        synchronized (this) {
            running = false;
            stoppedGeneration = ++generation;
            if (future != null) future.cancel(true);
            future = null;
            stopping = scheduler;
            scheduler = null;
        }
        // 진행 중인 연결 요청에 인터럽트를 보낸 뒤 구독 레지스트리 갱신이 끝나기를 기다립니다.
        if (stopping != null) stopping.shutdown();
        synchronized (applyMonitor) {
            if (!running && generation == stoppedGeneration) registry.replaceOrderStocks(Collections.emptySet());
        }
    }

    @Override
    public boolean isRunning() { return running; }

    protected ThreadPoolTaskScheduler createScheduler() { return new ThreadPoolTaskScheduler(); }
}
