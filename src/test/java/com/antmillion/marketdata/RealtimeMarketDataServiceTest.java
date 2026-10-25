package com.antmillion.marketdata;

import com.antmillion.kis.manager.KisWebSocketManager;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.marketdata.dto.OrderBookLevel;
import com.antmillion.marketdata.dto.RealtimeOrderBook;
import com.antmillion.marketdata.dto.RealtimePrice;
import com.antmillion.marketdata.provider.RealtimeMarketDataReceiver;
import com.antmillion.marketdata.service.RealtimeMarketDataService;
import com.antmillion.stock.service.TradingEngineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.ExecutorSubscribableChannel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 공통 DTO를 직접 전달하며 DB·설정 파일·외부 연결 없이 방송/체결 계약을 검증합니다. */
public class RealtimeMarketDataServiceTest {
    private final List<String> events = new ArrayList<>();
    private final List<Message<?>> messages = new ArrayList<>();
    private final RuntimeException failure = new IllegalStateException("synthetic execution failure");
    private String failingDirection;
    private final TradingEngineService engine = new TradingEngineService(null, null, null, null, null) {
        @Override public void processExecution(String stock, String direction, int price, int quantity) {
            events.add(stock + ":" + direction + ":" + price + ":" + quantity);
            if (direction.equals(failingDirection)) throw failure;
        }
    };
    private final ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
    private final SimpMessagingTemplate template = new SimpMessagingTemplate(channel);
    private final RealtimeMarketDataReceiver receiver = new RealtimeMarketDataService(template, engine);

    public RealtimeMarketDataServiceTest() {
        channel.subscribe(message -> {
            messages.add(message);
            events.add("broadcast");
        });
    }

    @Test
    public void providerIndependentPriceKeepsLegacyTopicFieldsAndStringTypes() throws Exception {
        receiver.onPrice(new RealtimePrice("ALT001", "001100", "5", "-020", "-1.25", "51.30"));
        assertEquals(List.of("broadcast"), events);
        assertEquals("/topic/kis-trade/presentALT001", messages.get(0).getHeaders().get("simpDestination"));
        Map<?, ?> data = (Map<?, ?>) messages.get(0).getPayload();
        assertEquals(Map.of("mkscShrnIscd", "ALT001", "stckPrpr", "001100", "prdySign", "5",
                "prdyVrss", "-020", "prdyCtrt", "-1.25", "shnuRate", "51.30"), data);
        JsonNode json = new ObjectMapper().valueToTree(data);
        json.elements().forEachRemaining(value -> assertTrue(value.isTextual()));
    }

    @Test
    public void missingCommonPriceValuesRemainNullOrEmpty() {
        receiver.onPrice(new RealtimePrice("ALT001", "", null, "", null, null));
        Map<?, ?> data = (Map<?, ?>) messages.get(0).getPayload();
        assertEquals(6, data.size());
        assertEquals("", data.get("stckPrpr"));
        assertNull(data.get("prdySign"));
        assertEquals("", data.get("prdyVrss"));
        assertNull(data.get("prdyCtrt"));
        assertNull(data.get("shnuRate"));
    }

    @Test
    public void allFiveLevelsBroadcastBeforeOppositeSideExecutions() {
        List<OrderBookLevel> asks = new ArrayList<>();
        List<OrderBookLevel> bids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            asks.add(new OrderBookLevel("0" + (1100 + i), "0" + (20 + i)));
            bids.add(new OrderBookLevel("0" + (1000 - i), "0" + (30 + i)));
        }
        receiver.onOrderBook(new RealtimeOrderBook("ALT001", asks, bids));
        assertEquals(List.of("broadcast", "ALT001:BUY:1101:21", "ALT001:SELL:999:31"), events);
        assertEquals("/topic/kis-trade/ask-bidALT001", messages.get(0).getHeaders().get("simpDestination"));
        Map<?, ?> data = (Map<?, ?>) messages.get(0).getPayload();
        assertEquals(21, data.size());
        assertEquals("ALT001", data.get("mkscShrnIscd"));
        for (int i = 1; i <= 5; i++) {
            assertEquals("0" + (1100 + i), data.get("askP" + i));
            assertEquals("0" + (1000 - i), data.get("bidP" + i));
            assertEquals("0" + (20 + i), data.get("askPrsqn" + i));
            assertEquals("0" + (30 + i), data.get("bidPrsqn" + i));
        }
    }

    @Test
    public void numericParsingKeepsTrimSignsZeroFallbackAndIndependentSides() {
        List<String> invalid = Arrays.asList(null, "", " ", "bad", "2.5", "2147483648", "-2147483649");
        for (String value : invalid) {
            events.clear();
            receiver.onOrderBook(book(value, value, " +01000 ", " 03 "));
            assertEquals(List.of("broadcast", "ALT001:BUY:0:0", "ALT001:SELL:1000:3"), events);
            Map<?, ?> data = (Map<?, ?>) messages.get(messages.size() - 1).getPayload();
            assertEquals(value, data.get("askP1"));
            assertEquals(value, data.get("askPrsqn1"));
        }
        events.clear();
        receiver.onOrderBook(book("-1", "0", "2147483647", "-2147483648"));
        assertEquals(List.of("broadcast", "ALT001:BUY:-1:0", "ALT001:SELL:2147483647:-2147483648"), events);
    }

    @Test
    public void buyFailureStillStopsSellAndPropagatesTheSameException() {
        failingDirection = "BUY";
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> receiver.onOrderBook(book("1100", "2", "1000", "3"))));
        assertEquals(List.of("broadcast", "ALT001:BUY:1100:2"), events);
    }

    @Test
    public void sellFailureStillFollowsBroadcastAndBuy() {
        failingDirection = "SELL";
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> receiver.onOrderBook(book("1100", "2", "1000", "3"))));
        assertEquals(List.of("broadcast", "ALT001:BUY:1100:2", "ALT001:SELL:1000:3"), events);
    }

    @Test
    public void broadcastFailurePreventsBothExecutionCalls() {
        SimpMessagingTemplate failing = new SimpMessagingTemplate(channel) {
            @Override public void convertAndSend(String destination, Object payload) { throw failure; }
        };
        RealtimeMarketDataReceiver service = new RealtimeMarketDataService(failing, engine);
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> service.onOrderBook(book("1100", "2", "1000", "3"))));
        assertTrue(events.isEmpty());
    }

    @Test
    public void orderBookCopiesBothListsAndRequiresFiveLevels() {
        List<OrderBookLevel> levels = new ArrayList<>(book("1100", "2", "1000", "3").getAsks());
        RealtimeOrderBook book = new RealtimeOrderBook("ALT001", levels, levels);
        levels.set(0, new OrderBookLevel("9999", "99"));
        assertEquals("1100", book.getAsks().get(0).getPrice());
        assertEquals("1100", book.getBids().get(0).getPrice());
        assertThrows(UnsupportedOperationException.class, () -> book.getAsks().clear());
        assertThrows(UnsupportedOperationException.class, () -> book.getBids().clear());
        assertThrows(IllegalArgumentException.class, () -> new RealtimeOrderBook("ALT001", List.of(), levels));
        assertThrows(IllegalArgumentException.class, () -> new RealtimeOrderBook("ALT001", levels, List.of()));
    }

    @Test
    public void springInjectsCommonReceiverIntoActualManagerWithoutExternalCallsInMockProfile() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("dev-mock");
            context.registerBean(SimpMessagingTemplate.class, () -> template);
            context.registerBean(TradingEngineService.class, () -> engine);
            context.registerBean(KisApiService.class, () -> new KisApiService(null, null, null, null, null,
                    null, null, null, null, null, com.antmillion.marketdata.CircuitBreakerTestSupport.defaults()));
            context.register(RealtimeMarketDataService.class, KisWebSocketManager.class);
            context.refresh();
            assertTrue(context.getBean(RealtimeMarketDataReceiver.class) instanceof RealtimeMarketDataService);
            assertEquals(1, context.getBeansOfType(RealtimeMarketDataReceiver.class).size());
            assertFalse(context.getBean(KisWebSocketManager.class).isRunning());
        }
    }

    private RealtimeOrderBook book(String askPrice, String askQty, String bidPrice, String bidQty) {
        List<OrderBookLevel> asks = new ArrayList<>();
        List<OrderBookLevel> bids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            asks.add(new OrderBookLevel(askPrice, askQty));
            bids.add(new OrderBookLevel(bidPrice, bidQty));
        }
        return new RealtimeOrderBook("ALT001", asks, bids);
    }
}
