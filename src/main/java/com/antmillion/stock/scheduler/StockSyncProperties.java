package com.antmillion.stock.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
public class StockSyncProperties {
    private final boolean enabled;
    private final String cron;
    private final ZoneId zone;

    public StockSyncProperties(@Value("${stock.sync.enabled:false}") boolean enabled,
            @Value("${stock.sync.cron:0 0 18 * * *}") String cron,
            @Value("${stock.sync.zone:Asia/Seoul}") String zone) {
        CronExpression.parse(cron);
        this.enabled = enabled;
        this.cron = cron;
        this.zone = ZoneId.of(zone);
    }

    public boolean isEnabled() { return enabled; }
    public String getCron() { return cron; }
    public ZoneId getZone() { return zone; }
}
