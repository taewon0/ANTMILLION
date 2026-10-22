package com.antmillion.kis.service;

import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisRestRateLimitConfig;
import com.antmillion.kis.dto.KisRestRequestScope;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

@Slf4j
@Component
public class RateLimitedKisApiClient {
    private final RedisTemplate<String, Object> redisTemplate;
    private final KisRestRateLimitConfig limits;
    private final TimeMeter timeMeter;
    // JVM 내에서 같은 환경·계좌의 시세 REST 예산을 공유합니다. 자동 퇴거로 예산을 재생성하지 않습니다.
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public RateLimitedKisApiClient(RedisTemplate<String, Object> redisTemplate, KisConfig config,
                                   KisRestRateLimitConfig limits) {
        this(redisTemplate, config, limits, TimeMeter.SYSTEM_NANOTIME);
    }

    RateLimitedKisApiClient(RedisTemplate<String, Object> redisTemplate, KisConfig config,
                            KisRestRateLimitConfig limits, TimeMeter timeMeter) {
        this.redisTemplate = redisTemplate;
        this.limits = limits;
        this.timeMeter = timeMeter;
        // 누락된 계좌 설정은 첫 외부 요청 이전, bean 생성 시 거부합니다.
        new KisRestRequestScope(config.getBaseUrl(), config.accountNo, config.acntPrdtCd,
                config.appKey, config.appSecret);
    }

    private Bucket bucket(KisRestRequestScope scope) {
        return buckets.computeIfAbsent(scope.getBudgetKey(), key -> {
            int perSecond = limits.tokensPerSecond(scope.getBaseUrl());
            Bandwidth limit = Bandwidth.builder().capacity(perSecond)
                    .refillGreedy(perSecond, Duration.ofSeconds(1)).build();
            return Bucket.builder().withCustomTimePrecision(timeMeter).addLimit(limit).build();
        });
    }

    // Rate Limit이 적용된 API 호출 (Redis 캐싱 O)
    public <T> T callWithCache(KisRestRequestScope scope, String cacheKey, Supplier<T> apiCall, long ttl) {
        Bucket bucket = bucket(scope);
        // 토큰 소비 시도
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            // 토큰 있음 → API 호출
            log.debug("Rate limit OK. Remaining tokens: {}", probe.getRemainingTokens());
            T result = apiCall.get();

            // Redis 캐싱 (cacheKey가 null이 아닌 경우만)
            if (cacheKey != null && result != null) {
                redisTemplate.opsForValue().set(cacheKey, result, ttl, TimeUnit.SECONDS);
                log.debug("Redis에 캐싱: key={}, ttl={}초", cacheKey, ttl);
            }

            return result;
        } else {
            // 토큰 없음 → Redis 캐시 조회
            log.warn("Rate limit exceeded. Trying Redis cache: key={}", cacheKey);

            if (cacheKey != null) {
                @SuppressWarnings("unchecked")
                T cached = (T) redisTemplate.opsForValue().get(cacheKey);

                if (cached != null) {
                    log.info("Redis 캐시 반환: key={}", cacheKey);
                    return cached;
                }
            }

            // 캐시도 없음 → 재시도 로직
            long nanosToWait = probe.getNanosToWaitForRefill();
            long millisToWait = nanosToWait / 1_000_000;

            // 대기 시간이 1초 이하면 재시도
            if (millisToWait <= 1000) {
                log.info("Rate limit exceeded but retrying after {}ms", millisToWait);

                try {
                    // 토큰 리필 대기
                    Thread.sleep(millisToWait + 50); // 여유있게 50ms 추가

                    // 재시도
                    ConsumptionProbe retryProbe = bucket.tryConsumeAndReturnRemaining(1);
                    if (retryProbe.isConsumed()) {
                        log.info("Retry successful. Remaining tokens: {}", retryProbe.getRemainingTokens());
                        T result = apiCall.get();

                        // Redis 캐싱
                        if (cacheKey != null && result != null) {
                            redisTemplate.opsForValue().set(cacheKey, result, ttl, TimeUnit.SECONDS);
                        }

                        return result;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Retry interrupted", e);
                }
            }

            // 재시도 실패 또는 대기 시간 너무 김 → 429 예외 발생
            log.error("Rate limit exceeded and no cache available. Wait: {}ms", millisToWait);
            throw new RateLimitExceededException(nanosToWait);
        }
    }

    // Rate Limit이 적용된 API 호출 (Redis 캐싱 X)
    public <T> T callWithoutCache(KisRestRequestScope scope, Supplier<T> apiCall) {
        Bucket bucket = bucket(scope);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            log.debug("Rate limit OK (no cache). Remaining tokens: {}", probe.getRemainingTokens());
            return apiCall.get();
        } else {
            // 캐시 없이 바로 429 예외
            long nanosToWait = probe.getNanosToWaitForRefill();
            log.error("Rate limit exceeded (no cache). Wait: {}ms", nanosToWait / 1_000_000);
            throw new RateLimitExceededException(nanosToWait);
        }
    }

    // 남은 토큰 수 조회 - 모니터링용
    public long getAvailableTokens(KisRestRequestScope scope) {
        return bucket(scope).getAvailableTokens();
    }

}
