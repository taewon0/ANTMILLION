package com.antmillion.stock.scheduler;

import com.antmillion.kis.manager.KisSubscriptionRegistry;
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

/** Restore at startup and reconcile committed orders, independently of browser sessions. */
@Slf4j
@Component
@Profile("!dev-mock")
public class OrderQuoteSubscriptionScheduler implements SmartLifecycle {
    private final StockOrderMapper orders;
    private final KisSubscriptionRegistry registry;
    private final TransactionTemplate readTransaction;
    private final Object applyMonitor = new Object();
    private ThreadPoolTaskScheduler scheduler;
    private ScheduledFuture<?> future;
    private volatile boolean running;
    private volatile long generation;

    public OrderQuoteSubscriptionScheduler(StockOrderMapper orders, KisSubscriptionRegistry registry,
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
            // This overload runs once immediately, then one second after each completed pass.
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
                // A stopped/replaced worker must never restore references after shutdown.
                if (running && generation == startedGeneration) {
                    registry.replaceOrderStocks(new HashSet<>(stockCodes));
                }
            }
        } catch (RuntimeException e) {
            // A DB failure is not an empty snapshot. Preserve references until a successful read.
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
        // Interrupt a pending connection request before waiting for its registry update to finish.
        if (stopping != null) stopping.shutdown();
        synchronized (applyMonitor) {
            if (!running && generation == stoppedGeneration) registry.replaceOrderStocks(Collections.emptySet());
        }
    }

    @Override
    public boolean isRunning() { return running; }

    protected ThreadPoolTaskScheduler createScheduler() { return new ThreadPoolTaskScheduler(); }
}
