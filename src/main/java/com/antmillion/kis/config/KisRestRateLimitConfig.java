package com.antmillion.kis.config;

import com.antmillion.kis.constant.KisApiConstant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 일반 고객의 공식 상한 이하로만 설정하며, 신규 고객은 실전 값을 직접 낮춥니다. */
@Component
public class KisRestRateLimitConfig {
    private final int realPerSecond;
    private final int virtualPerSecond;

    public KisRestRateLimitConfig(
            @Value("${kis.rest.rate-limit.real-per-second:18}") int realPerSecond,
            @Value("${kis.rest.rate-limit.virtual-per-second:1}") int virtualPerSecond) {
        if (realPerSecond < 1 || realPerSecond > 18 || virtualPerSecond != 1) {
            throw new IllegalArgumentException("KIS REST 호출 제한은 실전 1~18, 모의 1건/초로 설정해야 합니다.");
        }
        this.realPerSecond = realPerSecond;
        this.virtualPerSecond = virtualPerSecond;
    }

    public int tokensPerSecond(String baseUrl) {
        return KisApiConstant.VIRTUAL_BASE_URL.equals(baseUrl) ? virtualPerSecond : realPerSecond;
    }
}
