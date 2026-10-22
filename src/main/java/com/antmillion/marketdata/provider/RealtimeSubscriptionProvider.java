package com.antmillion.marketdata.provider;

/** 실시간 전송의 승인 완료가 아니라 종목·종류별 희망 구독 상태를 요청합니다. */
public interface RealtimeSubscriptionProvider {
    enum Kind { CURRENT_PRICE, ORDER_BOOK }

    /** 같은 종목·종류의 중복 요청은 하나로 유지하며 연결·복원은 구현체가 담당합니다. */
    void subscribe(String stockCode, Kind kind);

    /** 마지막 소유자가 사라진 종목·종류의 희망 구독을 제거합니다. */
    void unsubscribe(String stockCode, Kind kind);
}
