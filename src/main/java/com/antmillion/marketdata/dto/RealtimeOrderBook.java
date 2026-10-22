package com.antmillion.marketdata.dto;

import lombok.Value;

import java.util.List;

/** 매도·매수 각각 최우선부터 5단계이며 원본 문자열·순서를 보존합니다. */
@Value
public class RealtimeOrderBook {
    String stockCode;
    List<OrderBookLevel> asks;
    List<OrderBookLevel> bids;

    public RealtimeOrderBook(String stockCode, List<OrderBookLevel> asks, List<OrderBookLevel> bids) {
        if (asks.size() != 5 || bids.size() != 5) {
            throw new IllegalArgumentException("Realtime order book requires five levels per side");
        }
        this.stockCode = stockCode;
        this.asks = List.copyOf(asks);
        this.bids = List.copyOf(bids);
    }
}
