package com.antmillion.marketdata.provider;

import com.antmillion.marketdata.dto.RealtimeOrderBook;
import com.antmillion.marketdata.dto.RealtimePrice;

/** 유효한 현재 연결에서 해석한 시세를 동기 전달합니다. 연결·ACK·복원은 Provider 책임입니다. */
public interface RealtimeMarketDataReceiver {
    void onPrice(RealtimePrice price);

    void onOrderBook(RealtimeOrderBook orderBook);
}
