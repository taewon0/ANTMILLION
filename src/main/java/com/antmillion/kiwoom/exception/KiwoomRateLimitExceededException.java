package com.antmillion.kiwoom.exception;

import lombok.Getter;

/** 로컬 호출 거절입니다. HTTP 경로는 429로 매핑하며 자동 재시도하지 않습니다. */
@Getter
public final class KiwoomRateLimitExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final long nanosToWait;
    private final long retryAfterSeconds;

    public KiwoomRateLimitExceededException(long nanosToWait) {
        super("Kiwoom REST 호출 한도를 소진했습니다.");
        this.nanosToWait = nanosToWait;
        this.retryAfterSeconds = Math.max(1, (nanosToWait - 1) / 1_000_000_000L + 1);
    }
}
