package com.antmillion.stock.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
public class StockSyncScheduler implements SmartLifecycle {
    private final StockSyncJob job;
    private final StockSyncProperties properties;
    private ThreadPoolTaskScheduler scheduler;
    private ScheduledFuture<?> future;
    private volatile boolean running;

    public StockSyncScheduler(StockSyncJob job, StockSyncProperties properties) {
        this.job = job;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running || !properties.isEnabled()) return;
        // 기존 스프링 @Scheduled 작업의 실행기를 유지하도록 전용 스케줄러를 사용합니다.
        scheduler = createScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stock-sync-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        try {
            scheduler.initialize();
            future = scheduler.schedule(job::runDailySync,
                    new CronTrigger(properties.getCron(), TimeZone.getTimeZone(properties.getZone())));
            if (future == null) throw new IllegalStateException("Stock sync cron has no next execution");
            running = true;
            log.info("STOCK_SYNC_SCHEDULE cron={} zone={}", properties.getCron(), properties.getZone());
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    @Override
    public synchronized void stop() {
        if (future != null) future.cancel(false);
        if (scheduler != null) scheduler.shutdown();
        future = null;
        scheduler = null;
        running = false;
    }

    @Override
    public boolean isRunning() { return running; }

    protected ThreadPoolTaskScheduler createScheduler() {
        return new ThreadPoolTaskScheduler();
    }
}
