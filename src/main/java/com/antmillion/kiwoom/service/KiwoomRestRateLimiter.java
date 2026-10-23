package com.antmillion.kiwoom.service;

import com.antmillion.kiwoom.config.KiwoomRestRateLimitConfig;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 같은 limiter의 환경·앱 키별 JVM 예산입니다. 토큰 값·시크릿은 예산을 나누지 않습니다. */
@Component
public final class KiwoomRestRateLimiter {
    private final KiwoomRestRateLimitConfig limits;
    private final TimeMeter timeMeter;
    // 자동 퇴거로 소진된 예산을 재생성하지 않습니다. 다른 JVM/프로그램과는 공유하지 않습니다.
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public KiwoomRestRateLimiter(KiwoomRestRateLimitConfig limits) {
        this(limits, TimeMeter.SYSTEM_NANOTIME);
    }

    KiwoomRestRateLimiter(KiwoomRestRateLimitConfig limits, TimeMeter timeMeter) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.timeMeter = Objects.requireNonNull(timeMeter, "timeMeter");
    }

    public void acquireCurrentPrice(KiwoomTokenService tokens) {
        acquire(tokens, tokens.getCurrentPriceBudgetKey());
    }

    public void acquirePeriodChart(KiwoomTokenService tokens, String apiId) {
        if (apiId == null || !java.util.Set.of("ka10081", "ka10082", "ka10083", "ka10094").contains(apiId)) {
            throw new IllegalArgumentException("Kiwoom 기간별 차트 TR이 유효하지 않습니다.");
        }
        acquire(tokens, tokens.getQueryBudgetKey(apiId));
    }

    public void acquireMinuteChart(KiwoomTokenService tokens) {
        acquire(tokens, tokens.getQueryBudgetKey("ka10080"));
    }

    public void acquireMarketIndexChart(KiwoomTokenService tokens) {
        acquire(tokens, tokens.getQueryBudgetKey("ka20006"));
    }

    public void acquireStockRanking(KiwoomTokenService tokens) {
        acquire(tokens, tokens.getQueryBudgetKey("ka10030"));
    }

    private void acquire(KiwoomTokenService tokens, String budgetKey) {
        int perSecond = limits.tokensPerSecond(tokens.getBaseUrl());
        Bucket bucket = buckets.computeIfAbsent(budgetKey, key -> {
            Bandwidth limit = Bandwidth.builder().capacity(perSecond)
                    .refillGreedy(perSecond, Duration.ofSeconds(1)).build();
            return Bucket.builder().withCustomTimePrecision(timeMeter).addLimit(limit).build();
        });
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw new KiwoomRateLimitExceededException(probe.getNanosToWaitForRefill());
        }
    }
}
