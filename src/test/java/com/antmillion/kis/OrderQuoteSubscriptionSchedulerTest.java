package com.antmillion.kis;

import com.antmillion.kis.manager.KisSubscriptionRegistry;
import com.antmillion.stock.mapper.StockOrderMapper;
import com.antmillion.stock.scheduler.OrderQuoteSubscriptionScheduler;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** No properties, database or external transport; private timers are closed by each lifecycle test. */
public class OrderQuoteSubscriptionSchedulerTest {
    @Test
    public void realContextStartsRestorationImmediatelyAndClosesPrivateWorker() throws Exception {
        StartupProvider provider;
        OrderQuoteSubscriptionScheduler scheduler;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(StartupConfig.class)) {
            provider = context.getBean(StartupProvider.class);
            scheduler = context.getBean(OrderQuoteSubscriptionScheduler.class);
            assertTrue(provider.subscribed.await(5, TimeUnit.SECONDS));
            assertEquals(1, provider.acquired.size());
            assertTrue(scheduler.isRunning());
        }
        assertFalse(scheduler.isRunning());
        assertEquals(provider.acquired, provider.released);
    }

    @Configuration
    @Import({KisSubscriptionRegistry.class, OrderQuoteSubscriptionScheduler.class})
    static class StartupConfig {
        @Bean public StartupProvider manager() { return new StartupProvider(); }
        @Bean public StockOrderMapper orders() { return mapper(() -> List.of("005930")); }
        @Bean public PlatformTransactionManager transactionManager() { return noDbTransaction(); }
    }

    static class StartupProvider extends KisSubscriptionRegistryTest.Provider {
        final CountDownLatch subscribed = new CountDownLatch(1);
        @Override public synchronized void subscribe(String stock, String trId) {
            super.subscribe(stock, trId);
            subscribed.countDown();
        }
    }

    @Test
    public void oneImmediateFixedDelayTaskIsIdempotentAndShutdownReleasesOnlyOrderOwner() throws Exception {
        try (KisSubscriptionRegistryTest.Wire wire = new KisSubscriptionRegistryTest.Wire()) {
            Worker worker = new Worker();
            OrderQuoteSubscriptionScheduler scheduler = scheduler(mapper(() -> List.of("005930")),
                    wire.context.getBean(KisSubscriptionRegistry.class), noDbTransaction(), worker);
            try {
                scheduler.start();
                scheduler.start();
                assertTrue(scheduler.isRunning());
                assertEquals(1, worker.registrations);
                assertEquals(1000, worker.delay);
                worker.task.run();
                org.springframework.web.socket.WebSocketSession browser = wire.connect("browser");
                wire.subscribe(browser, "book", "ask-bid005930");
                scheduler.stop();
                scheduler.stop();
                assertTrue(worker.closed);
                assertTrue(worker.future.cancelled);
                assertFalse(scheduler.isRunning());
                assertTrue(wire.provider.released.isEmpty());
                worker.task.run();
                wire.disconnect(browser);
                assertEquals(wire.provider.acquired, wire.provider.released);
            } finally { scheduler.stop(); }
        }
    }

    @Test
    public void databaseFailurePreservesOrdersAndNextSuccessfulSnapshotRepairsState() {
        try (KisSubscriptionRegistryTest.Wire wire = new KisSubscriptionRegistryTest.Wire()) {
            AtomicReference<List<String>> stocks = new AtomicReference<>(List.of("005930"));
            AtomicBoolean failed = new AtomicBoolean();
            Worker worker = new Worker();
            OrderQuoteSubscriptionScheduler scheduler = scheduler(mapper(() -> {
                if (failed.get()) throw new IllegalStateException("synthetic DB failure");
                return stocks.get();
            }), wire.context.getBean(KisSubscriptionRegistry.class), noDbTransaction(), worker);
            try {
                scheduler.start();
                worker.task.run();
                failed.set(true);
                worker.task.run();
                assertTrue(wire.provider.released.isEmpty());
                failed.set(false);
                stocks.set(List.of());
                worker.task.run();
                assertEquals(wire.provider.acquired, wire.provider.released);
            } finally { scheduler.stop(); }
        }
    }

    @Test
    public void shutdownInterruptsPendingProviderRequestBeforeClearingOrderReference() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        KisSubscriptionRegistryTest.Provider provider = new KisSubscriptionRegistryTest.Provider() {
            @Override public void subscribe(String stock, String trId) {
                entered.countDown();
                try { new CountDownLatch(1).await(8, TimeUnit.SECONDS); }
                catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("synthetic interrupted connection", e);
                }
            }
        };
        KisSubscriptionRegistry registry = new KisSubscriptionRegistry(provider);
        OrderQuoteSubscriptionScheduler scheduler = new OrderQuoteSubscriptionScheduler(
                mapper(() -> List.of("005930")), registry, noDbTransaction());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            scheduler.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            executor.submit(() -> { scheduler.stop(); }).get(5, TimeUnit.SECONDS);
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertFalse(scheduler.isRunning());
            assertTrue(provider.acquired.isEmpty());
            assertTrue(provider.released.isEmpty());
        } finally {
            scheduler.stop();
            executor.shutdownNow();
        }
    }

    @Test
    public void interruptedOldSnapshotCannotReacquireAfterStopOrRestart() throws Exception {
        try (KisSubscriptionRegistryTest.Wire wire = new KisSubscriptionRegistryTest.Wire()) {
            CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
            AtomicBoolean block = new AtomicBoolean(true);
            Worker worker = new Worker();
            OrderQuoteSubscriptionScheduler scheduler = scheduler(mapper(() -> {
                if (block.getAndSet(false)) {
                    entered.countDown();
                    try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new IllegalStateException(e); }
                    return List.of("005930");
                }
                return List.of("000660");
            }), wire.context.getBean(KisSubscriptionRegistry.class), noDbTransaction(), worker);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                scheduler.start();
                Runnable oldTask = worker.task;
                Future<?> oldRead = executor.submit(oldTask);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                scheduler.stop();
                scheduler.start();
                worker.task.run();
                resume.countDown();
                oldRead.get(5, TimeUnit.SECONDS);
                assertEquals(1, wire.provider.acquired.size());
                assertTrue(wire.provider.acquired.get(0).startsWith("000660:"));
            } finally {
                resume.countDown();
                scheduler.stop();
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void schedulingFailureClosesAllocatedWorker() {
        try (KisSubscriptionRegistryTest.Wire wire = new KisSubscriptionRegistryTest.Wire()) {
            Worker worker = new Worker() {
                @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay) {
                    throw new IllegalStateException("synthetic registration failure");
                }
            };
            OrderQuoteSubscriptionScheduler scheduler = scheduler(mapper(List::of),
                    wire.context.getBean(KisSubscriptionRegistry.class), noDbTransaction(), worker);
            assertThrows(IllegalStateException.class, scheduler::start);
            assertTrue(worker.closed);
            assertFalse(scheduler.isRunning());
        }
    }

    @Test
    public void mockProfileExcludesWorkerEvenWithoutRealDependencies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("dev-mock");
            context.register(OrderQuoteSubscriptionScheduler.class);
            context.refresh();
            assertTrue(context.getBeansOfType(OrderQuoteSubscriptionScheduler.class).isEmpty());
        }
    }

    static OrderQuoteSubscriptionScheduler scheduler(StockOrderMapper orders, KisSubscriptionRegistry registry,
            PlatformTransactionManager transactionManager, Worker worker) {
        return new OrderQuoteSubscriptionScheduler(orders, registry, transactionManager) {
            @Override protected ThreadPoolTaskScheduler createScheduler() { return worker; }
        };
    }

    static StockOrderMapper mapper(Supplier<List<String>> stocks) {
        return (StockOrderMapper) Proxy.newProxyInstance(StockOrderMapper.class.getClassLoader(),
                new Class<?>[]{StockOrderMapper.class}, (proxy, method, args) -> {
                    if ("selectPendingQuoteStockCodes".equals(method.getName())) return stocks.get();
                    throw new AssertionError("Unexpected mapper operation: " + method.getName());
                });
    }

    private static PlatformTransactionManager noDbTransaction() {
        return new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                assertTrue(definition.isReadOnly());
                assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, definition.getIsolationLevel());
                assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
    }

    static class Worker extends ThreadPoolTaskScheduler {
        private static final long serialVersionUID = 1L;
        Runnable task;
        long delay;
        int registrations;
        boolean closed;
        StubFuture future;
        @Override public void initialize() { }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay) {
            this.task = task; this.delay = delay; registrations++;
            future = new StubFuture();
            return future;
        }
        @Override public void shutdown() { closed = true; }
    }

    static class StubFuture implements ScheduledFuture<Object> {
        boolean cancelled;
        @Override public boolean cancel(boolean interrupt) { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return cancelled; }
        @Override public Object get() { throw new UnsupportedOperationException(); }
        @Override public Object get(long timeout, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public long getDelay(TimeUnit unit) { return Long.MAX_VALUE; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
