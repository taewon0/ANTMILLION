package com.antmillion.kiwoom.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 국내주식 조회 한도입니다. 같은 계좌의 여러 앱 키 간 예산 공유는 후속입니다. */
@Component
public final class KiwoomRestRateLimitConfig {
    private final int realPerSecond;
    private final int virtualPerSecond;

    public KiwoomRestRateLimitConfig(
            @Value("${kiwoom.rest.rate-limit.real-per-second:5}") int realPerSecond,
            @Value("${kiwoom.rest.rate-limit.virtual-per-second:1}") int virtualPerSecond) {
        if (realPerSecond < 1 || realPerSecond > 5 || virtualPerSecond != 1) {
            throw new IllegalArgumentException("Kiwoom REST 호출 제한은 실전 1~5, 모의 1건/초로 설정해야 합니다.");
        }
        this.realPerSecond = realPerSecond;
        this.virtualPerSecond = virtualPerSecond;
    }

    public int tokensPerSecond(String baseUrl) {
        if ("https://api.kiwoom.com".equals(baseUrl)) return realPerSecond;
        if ("https://mockapi.kiwoom.com".equals(baseUrl)) return virtualPerSecond;
        throw new IllegalArgumentException("Kiwoom REST 환경이 유효하지 않습니다.");
    }
}
