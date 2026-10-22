package com.antmillion.kis.service;

import com.antmillion.marketdata.dto.OrderBookLevel;
import com.antmillion.marketdata.dto.RealtimeOrderBook;
import com.antmillion.marketdata.dto.RealtimePrice;
import com.antmillion.marketdata.provider.RealtimeMarketDataReceiver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import static com.antmillion.kis.constant.KisWebSocketTrId.isAskBidTrId;
import static com.antmillion.kis.constant.KisWebSocketTrId.isPresentPriceTrId;

/** KIS 프레임/TR/필드 위치를 공통 시세로 변환하며 파싱·전달 오류는 Handler로 전파합니다. */
@Slf4j
@RequiredArgsConstructor
public class KisRealtimeMessageAdapter {
    private final RealtimeMarketDataReceiver receiver;

    public void onMessage(String payload) {
        String[] parts = payload.split("\\|");
        String trId = parts[1];
        if (isPresentPriceTrId(trId)) {
            String[] data = parts[3].split("\\^");
            // 기존의 정확히 "0"인 현재가 제외와 split의 빈 값 처리를 유지합니다.
            if (data[2].equals("0")) return;
            receiver.onPrice(new RealtimePrice(data[0], data[2], data[3], data[4], data[5], data[22]));
        } else if (isAskBidTrId(trId)) {
            String[] data = parts[3].split("\\^");
            List<OrderBookLevel> asks = new ArrayList<>();
            List<OrderBookLevel> bids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                asks.add(new OrderBookLevel(data[3 + i], data[23 + i]));
                bids.add(new OrderBookLevel(data[13 + i], data[33 + i]));
            }
            receiver.onOrderBook(new RealtimeOrderBook(data[0], asks, bids));
        } else {
            log.info("### [알림] 현재가/호가 데이터 아님 ###");
        }
    }
}
