package com.antmillion.stock.scheduler;

import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;
import java.util.Map;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class StockSyncSchedulerTest {
    @Test
    public void defaultsAreDisabledAndDailyAtSixPmSeoul() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(StockSyncProperties.class)) {
            StockSyncProperties properties = context.getBean(StockSyncProperties.class);
            assertFalse(properties.isEnabled());
            assertEquals("0 0 18 * * *", properties.getCron());
            assertEquals("Asia/Seoul", properties.getZone().getId());
        }
    }

    @Test
    public void disabledLifecycleDoesNotAllocateSchedulerOrCallJob() {
        StockSyncProperties properties = properties(false);
        CountingJob job = new CountingJob(properties);
        StockSyncScheduler scheduler = new StockSyncScheduler(job, properties) {
            @Override protected ThreadPoolTaskScheduler createScheduler() { throw new AssertionError("disabled scheduler allocated"); }
        };
        scheduler.start();
        assertFalse(scheduler.isRunning());
        assertEquals(0, job.calls);
        assertEquals(StockSyncJob.Outcome.DISABLED, new StockSyncJob(properties, null, null, null, null,
                new DataSourceTransactionManager()).runDailySync());
        scheduler.stop();
    }

    @Test
    public void explicitConfigurationOverridesDefaults() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-settings", Map.of(
                    "stock.sync.enabled", "true", "stock.sync.cron", "0 30 18 * * *", "stock.sync.zone", "UTC")));
            context.register(StockSyncProperties.class);
            context.refresh();
            StockSyncProperties properties = context.getBean(StockSyncProperties.class);
            assertTrue(properties.isEnabled());
            assertEquals("0 30 18 * * *", properties.getCron());
            assertEquals("UTC", properties.getZone().getId());
        }
    }

    @Test
    public void registersOneFutureWithoutStartupCallAndCleansUp() {
        StockSyncProperties properties = properties(true);
        CountingJob job = new CountingJob(properties);
        CapturingScheduler worker = new CapturingScheduler();
        StockSyncScheduler scheduler = new StockSyncScheduler(job, properties) {
            @Override protected ThreadPoolTaskScheduler createScheduler() { return worker; }
        };
        scheduler.start();
        scheduler.start();
        assertTrue(scheduler.isRunning());
        assertEquals(1, worker.registrations);
        assertEquals(0, job.calls);
        worker.task.run();
        assertEquals(1, job.calls);
        scheduler.stop();
        scheduler.stop();
        assertTrue(worker.closed);
        assertTrue(worker.future.isCancelled());
        assertFalse(scheduler.isRunning());
    }

    @Test
    public void actualTriggerUsesSeoulEveryDayAndDoesNotCatchUpMissedTime() {
        CapturingScheduler worker = new CapturingScheduler();
        StockSyncProperties properties = properties(true);
        StockSyncScheduler scheduler = new StockSyncScheduler(new CountingJob(properties), properties) {
            @Override protected ThreadPoolTaskScheduler createScheduler() { return worker; }
        };
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            scheduler.start();
            Date before = Date.from(Instant.parse("2026-10-17T08:59:00Z"));
            assertEquals(Date.from(Instant.parse("2026-10-17T09:00:00Z")),
                    worker.trigger.nextExecutionTime(new SimpleTriggerContext(before, before, before)));
            Date after = Date.from(Instant.parse("2026-10-17T10:00:00Z"));
            assertEquals(Date.from(Instant.parse("2026-10-18T09:00:00Z")),
                    worker.trigger.nextExecutionTime(new SimpleTriggerContext(after, after, after)));
        } finally {
            scheduler.stop();
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void rejectsInvalidCronAndTimezoneEvenBeforeScheduling() {
        assertThrows(IllegalArgumentException.class, () -> new StockSyncProperties(true, "bad-cron", "Asia/Seoul"));
        assertThrows(java.time.DateTimeException.class, () -> new StockSyncProperties(true, "0 0 18 * * *", "bad/zone"));
    }

    @Test
    public void missingNextExecutionCleansAllocatedWorker() {
        StockSyncProperties properties = properties(true);
        CapturingScheduler worker = new CapturingScheduler() {
            @Override public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) { return null; }
        };
        StockSyncScheduler scheduler = new StockSyncScheduler(new CountingJob(properties), properties) {
            @Override protected ThreadPoolTaskScheduler createScheduler() { return worker; }
        };
        assertThrows(IllegalStateException.class, scheduler::start);
        assertTrue(worker.closed);
        assertFalse(scheduler.isRunning());
    }

    private static StockSyncProperties properties(boolean enabled) {
        return new StockSyncProperties(enabled, "0 0 18 * * *", "Asia/Seoul");
    }

    private static class CountingJob extends StockSyncJob {
        private int calls;
        CountingJob(StockSyncProperties properties) {
            super(properties, null, null, null, null, new DataSourceTransactionManager());
        }
        @Override public Outcome runDailySync() { calls++; return Outcome.SUCCEEDED; }
    }

    private static class CapturingScheduler extends ThreadPoolTaskScheduler {
        private Runnable task;
        private Trigger trigger;
        private int registrations;
        private boolean closed;
        private final StubFuture future = new StubFuture();
        @Override public void initialize() { }
        @Override public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            this.task = task; this.trigger = trigger; registrations++; return future;
        }
        @Override public void shutdown() { closed = true; }
    }

    private static class StubFuture implements ScheduledFuture<Object> {
        private boolean cancelled;
        @Override public boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return cancelled; }
        @Override public Object get() { throw new UnsupportedOperationException(); }
        @Override public Object get(long timeout, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public long getDelay(TimeUnit unit) { return Long.MAX_VALUE; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
