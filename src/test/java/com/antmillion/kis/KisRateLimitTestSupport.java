package com.antmillion.kis;

import com.antmillion.kis.dto.KisRestRequestScope;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import io.github.bucket4j.Bucket;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.List;

final class KisRateLimitTestSupport {
    private KisRateLimitTestSupport() { }

    static KisRestRequestScope scope(String baseUrl) {
        return new KisRestRequestScope(baseUrl, "synthetic-account", "01", "synthetic-key", "synthetic-secret");
    }

    @SuppressWarnings("unchecked")
    static void fixedCacheBudget(RateLimitedKisApiClient limiter, Bucket budget) {
        Map<String, Bucket> buckets = (Map<String, Bucket>) ReflectionTestUtils.getField(limiter, "buckets");
        // 기존 캐시 회귀의 환경 왕복 조회 횟수를 검증하는 공통 fixture입니다.
        // 실제 범위별 예산 분리·동시성·리필은 KisRestRateLimitTest에서 독립 버킷으로 검증합니다.
        for (String endpoint : List.of("https://kis.test", "https://real.kis.test", "https://virtual.kis.test")) {
            buckets.put(scope(endpoint).getBudgetKey(), budget);
        }
    }
}
