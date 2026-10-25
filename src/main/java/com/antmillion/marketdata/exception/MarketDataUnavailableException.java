package com.antmillion.marketdata.exception;

/** CB 차단에는 공급사 주소·자격증명·원인 예외를 포함하지 않습니다. */
public final class MarketDataUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MarketDataUnavailableException() {
        super("시세 제공 서비스가 일시적으로 중단되었습니다. 잠시 후 다시 시도해주세요.");
    }
}
