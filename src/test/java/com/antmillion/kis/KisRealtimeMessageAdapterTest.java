package com.antmillion.kis;

import com.antmillion.kis.handler.KisWebSocketHandler;
import com.antmillion.kis.service.KisRealtimeMessageAdapter;
import com.antmillion.marketdata.dto.OrderBookLevel;
import com.antmillion.marketdata.dto.RealtimeOrderBook;
import com.antmillion.marketdata.dto.RealtimePrice;
import com.antmillion.marketdata.provider.RealtimeMarketDataReceiver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.web.socket.TextMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** 합성 KIS 프레임과 공통 수신 대역만 사용하며 DB·설정·외부 연결은 로드하지 않습니다. */
public class KisRealtimeMessageAdapterTest {
    private final List<RealtimePrice> prices = new ArrayList<>();
    private final List<RealtimeOrderBook> books = new ArrayList<>();
    private final RealtimeMarketDataReceiver receiver = new RealtimeMarketDataReceiver() {
        @Override public void onPrice(RealtimePrice price) { prices.add(price); }
        @Override public void onOrderBook(RealtimeOrderBook book) { books.add(book); }
    };
    private final KisRealtimeMessageAdapter adapter = new KisRealtimeMessageAdapter(receiver);

    @Test
    public void bothCurrentPriceTrsMapRawStringsToTheCommonContract() {
        String[] fields = priceFields();
        fields[3] = "5";
        fields[4] = "-020";
        fields[5] = "-1.25";
        fields[22] = "51.30";
        for (String tr : List.of("H0STCNT0", "H0UNCNT0")) {
            adapter.onMessage(frame(tr, fields));
        }
        assertEquals(List.of(new RealtimePrice("005930", "001100", "5", "-020", "-1.25", "51.30"),
                new RealtimePrice("005930", "001100", "5", "-020", "-1.25", "51.30")), prices);
        assertTrue(books.isEmpty());
    }

    @Test
    public void bothOrderBookTrsKeepAllFiveSidesAndLevelsWithoutNumericParsing() {
        String[] fields = bookFields();
        for (int i = 1; i < fields.length; i++) fields[i] = "raw" + i;
        for (String tr : List.of("H0STASP0", "H0UNASP0")) adapter.onMessage(frame(tr, fields));
        assertEquals(2, books.size());
        for (RealtimeOrderBook book : books) {
            assertEquals("005930", book.getStockCode());
            for (int i = 0; i < 5; i++) {
                assertEquals(new OrderBookLevel("raw" + (3 + i), "raw" + (23 + i)), book.getAsks().get(i));
                assertEquals(new OrderBookLevel("raw" + (13 + i), "raw" + (33 + i)), book.getBids().get(i));
            }
        }
        assertTrue(prices.isEmpty());
    }

    @Test
    public void onlyExactZeroCurrentPriceIsSuppressedBeforeReadingRemainingFields() {
        adapter.onMessage("0|H0STCNT0|001|005930^0^0");
        String[] fields = priceFields();
        fields[2] = "0";
        adapter.onMessage(frame("H0UNCNT0", fields));
        assertTrue(prices.isEmpty());
        for (String value : List.of("00", " 0 ", "bad", "")) {
            fields[2] = value;
            adapter.onMessage(frame("H0STCNT0", fields));
        }
        assertEquals(4, prices.size());
        assertEquals("", prices.get(3).getCurrentPrice());
    }

    @Test
    public void interiorEmptyValuesStayEmptyAndTrailingEmptyKeepsLegacySplitRejection() throws Exception {
        String[] fields = bookFields();
        fields[3] = "";
        fields[23] = "";
        adapter.onMessage(frame("H0STASP0", fields));
        assertEquals(new OrderBookLevel("", ""), books.get(0).getAsks().get(0));
        fields[37] = "";
        handler(receiver).handleMessage(null, new TextMessage(frame("H0STASP0", fields)));
        assertEquals(1, books.size());
    }

    @Test
    public void malformedUnknownAndSystemFramesDoNotDispatchAndNextValidFrameStillWorks() throws Exception {
        KisWebSocketHandler handler = handler(receiver);
        for (String payload : List.of("bad", "0|H0STCNT0|001|005930^0^1100", "0|H0STASP0|001|005930^0",
                "0|UNKNOWN|001|unused", "{\"header\":{\"tr_id\":\"PINGPONG\"}}", "{bad-json")) {
            handler.handleMessage(null, new TextMessage(payload));
        }
        assertTrue(prices.isEmpty());
        assertTrue(books.isEmpty());
        handler.handleMessage(null, new TextMessage(frame("H0STCNT0", priceFields())));
        handler.handleMessage(null, new TextMessage(frame("H0UNASP0", bookFields())));
        assertEquals(1, prices.size());
        assertEquals(1, books.size());
    }

    @Test
    public void deliveryFailureIsCaughtByHandlerAndDoesNotStopNextMessage() throws Exception {
        RealtimeMarketDataReceiver failing = new RealtimeMarketDataReceiver() {
            @Override public void onPrice(RealtimePrice price) { throw new IllegalStateException("synthetic delivery failure"); }
            @Override public void onOrderBook(RealtimeOrderBook book) { books.add(book); }
        };
        KisWebSocketHandler handler = handler(failing);
        handler.handleMessage(null, new TextMessage(frame("H0STCNT0", priceFields())));
        handler.handleMessage(null, new TextMessage(frame("H0STASP0", bookFields())));
        assertEquals(1, books.size());
    }

    private KisWebSocketHandler handler(RealtimeMarketDataReceiver target) {
        return new KisWebSocketHandler(null, new ObjectMapper(), target, 0);
    }

    private String[] priceFields() {
        String[] fields = new String[23];
        Arrays.fill(fields, "0");
        fields[0] = "005930";
        fields[2] = "001100";
        return fields;
    }

    private String[] bookFields() {
        String[] fields = new String[38];
        Arrays.fill(fields, "0");
        fields[0] = "005930";
        return fields;
    }

    private String frame(String tr, String[] fields) { return "0|" + tr + "|001|" + String.join("^", fields); }
}
