package com.antmillion.marketdata.service;

import com.antmillion.marketdata.dto.OrderBookLevel;
import com.antmillion.marketdata.dto.RealtimeOrderBook;
import com.antmillion.marketdata.dto.RealtimePrice;
import com.antmillion.marketdata.provider.RealtimeMarketDataReceiver;
import com.antmillion.stock.service.TradingEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/** 기존 브라우저의 토픽·필드와 방송 후 매수→매도 체결 순서를 유지합니다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeMarketDataService implements RealtimeMarketDataReceiver {
    private final SimpMessagingTemplate messagingTemplate;
    private final TradingEngineService tradingEngineService;

    @Override
    public void onPrice(RealtimePrice price) {
        Map<String, String> data = new HashMap<>();
        data.put("mkscShrnIscd", price.getStockCode());
        data.put("stckPrpr", price.getCurrentPrice());
        data.put("prdySign", price.getChangeSign());
        data.put("prdyVrss", price.getChangeAmount());
        data.put("prdyCtrt", price.getChangeRate());
        data.put("shnuRate", price.getBuyRatio());
        messagingTemplate.convertAndSend("/topic/kis-trade/present" + price.getStockCode(), data);
    }

    @Override
    public void onOrderBook(RealtimeOrderBook orderBook) {
        Map<String, String> data = new HashMap<>();
        data.put("mkscShrnIscd", orderBook.getStockCode());
        for (int i = 0; i < 5; i++) {
            OrderBookLevel ask = orderBook.getAsks().get(i);
            OrderBookLevel bid = orderBook.getBids().get(i);
            data.put("askP" + (i + 1), ask.getPrice());
            data.put("bidP" + (i + 1), bid.getPrice());
            data.put("askPrsqn" + (i + 1), ask.getQuantity());
            data.put("bidPrsqn" + (i + 1), bid.getQuantity());
        }
        messagingTemplate.convertAndSend("/topic/kis-trade/ask-bid" + orderBook.getStockCode(), data);

        OrderBookLevel bid = orderBook.getBids().get(0);
        OrderBookLevel ask = orderBook.getAsks().get(0);
        int bestBidPrice = parseSafeInt(bid.getPrice());
        int bestBidQty = parseSafeInt(bid.getQuantity());
        int bestAskPrice = parseSafeInt(ask.getPrice());
        int bestAskQty = parseSafeInt(ask.getQuantity());
        tradingEngineService.processExecution(orderBook.getStockCode(), "BUY", bestAskPrice, bestAskQty);
        tradingEngineService.processExecution(orderBook.getStockCode(), "SELL", bestBidPrice, bestBidQty);
    }

    private int parseSafeInt(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("숫자 파싱 실패: [{}]", value);
            return 0;
        }
    }
}
