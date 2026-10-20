package com.antmillion.kis.constant;

import lombok.Getter;

import java.time.LocalTime;
import java.time.ZoneId;

public enum KisWebSocketTrId {
    // KRX (정규장 09:00 ~ 15:30)
    KRX_PRESENT("H0STCNT0", "실시간체결가-KRX"),
    KRX_ASK_BID("H0STASP0", "실시간호가-KRX"),
    // 통합 (시간외 15:30 ~ 20:00)
    UNIFIED_PRESENT("H0UNCNT0", "실시간체결가-통합"),
    UNIFIED_ASK_BID("H0UNASP0", "실시간호가-통합");

    @Getter
    private final String trId;
    @Getter
    private final String description;

    KisWebSocketTrId(String trId, String description) {
        this.trId = trId;
        this.description = description;
    }

    /**
     * 현재 시간에 맞는 실시간 체결가 tr_id 반환
     */
    public static String getCurrentPresentTrId() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Seoul"));
        LocalTime marketClose = LocalTime.of(15, 30);

        if (now.isBefore(marketClose)) {
            return KRX_PRESENT.getTrId(); // KRX 현재가 TR: H0STCNT0
        } else {
            return UNIFIED_PRESENT.getTrId(); // 통합 현재가 TR: H0UNCNT0
        }
    }

    /**
     * 현재 시간에 맞는 실시간 호가 tr_id 반환
     */
    public static String getCurrentAskBidTrId() {
        LocalTime now = LocalTime.now(ZoneId.of("Asia/Seoul"));
        LocalTime marketClose = LocalTime.of(15, 30);

        if (now.isBefore(marketClose)) {
            return KRX_ASK_BID.getTrId(); // KRX 호가 TR: H0STASP0
        } else {
            return UNIFIED_ASK_BID.getTrId(); // 통합 호가 TR: H0UNASP0
        }
    }

    /**
     * tr_id로부터 토픽 경로 생성
     */
    public static String getTopicPath(String trId, String stockCode) {
        if (trId.equals(KRX_PRESENT.getTrId()) || trId.equals(UNIFIED_PRESENT.getTrId())) {
            return "/topic/kis-trade/present" + stockCode;
        } else if (trId.equals(KRX_ASK_BID.getTrId()) || trId.equals(UNIFIED_ASK_BID.getTrId())) {
            return "/topic/kis-trade/ask-bid" + stockCode;
        }
        throw new IllegalArgumentException("Unknown tr_id: " + trId);
    }

    public static boolean isPresentPriceTrId(String trId) {
        return trId.equals(KRX_PRESENT.getTrId()) || trId.equals(UNIFIED_PRESENT.getTrId());
    }

    public static boolean isAskBidTrId(String trId) {
        return trId.equals(KRX_ASK_BID.getTrId()) ||  trId.equals(UNIFIED_ASK_BID.getTrId());
    }
}
