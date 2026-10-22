package com.antmillion.marketdata;

import com.antmillion.config.GlobalExceptionHandler;
import com.antmillion.kis.config.KisMarketDataProviderConfig;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.KisCurrentPriceProvider;
import com.antmillion.kis.service.KisInvestorFlowProvider;
import com.antmillion.kis.service.KisMarketIndexChartProvider;
import com.antmillion.kis.service.KisMinuteChartProvider;
import com.antmillion.kis.service.KisPeriodChartProvider;
import com.antmillion.kis.service.KisStockRankingProvider;
import com.antmillion.marketdata.dto.CurrentQuote;
import com.antmillion.marketdata.dto.InvestorFlow;
import com.antmillion.marketdata.dto.MarketIndexChartRequest;
import com.antmillion.marketdata.dto.MinuteChartRequest;
import com.antmillion.marketdata.dto.PeriodChartRequest;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
import com.antmillion.marketdata.provider.InvestorFlowProvider;
import com.antmillion.marketdata.provider.MarketDataProviderRegistration;
import com.antmillion.marketdata.provider.MarketDataProviderRegistry;
import com.antmillion.marketdata.provider.MarketIndexChartProvider;
import com.antmillion.marketdata.provider.MinuteChartProvider;
import com.antmillion.marketdata.provider.PeriodChartProvider;
import com.antmillion.marketdata.provider.StockRankingProvider;
import com.antmillion.marketdata.service.InvestorSignalService;
import org.junit.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 실제 등록·라우팅 구성과 KIS adapter를 생성하되 DB·Redis·HTTP·WS·스케줄러는 등록하지 않습니다. */
public class MarketDataRoutingTest {
    private static final List<Class<?>> CONTRACTS = List.of(CurrentPriceProvider.class, PeriodChartProvider.class,
            MinuteChartProvider.class, MarketIndexChartProvider.class, StockRankingProvider.class, InvestorFlowProvider.class);
    private static final List<Class<?>> ADAPTERS = List.of(KisCurrentPriceProvider.class, KisPeriodChartProvider.class,
            KisMinuteChartProvider.class, KisMarketIndexChartProvider.class, KisStockRankingProvider.class,
            KisInvestorFlowProvider.class);

    @Test
    public void absentSettingSelectsAllActualKisAdaptersWithoutAmbiguousInjection() {
        try (AnnotationConfigApplicationContext context = context(null)) {
            context.refresh();
            assertEquals("kis", context.getBean(MarketDataProviderRegistry.class).getSelectedId());
            assertEquals(6, context.getBean(MarketDataProviderRegistry.class).getSupportedContracts().size());
            for (int i = 0; i < CONTRACTS.size(); i++) {
                assertSame(context.getBean(ADAPTERS.get(i)), context.getBean(CONTRACTS.get(i)));
                assertEquals(2, context.getBeansOfType(CONTRACTS.get(i)).size());
            }
        }
    }

    @Test
    public void explicitKisSettingKeepsTheDefaultProvider() {
        try (AnnotationConfigApplicationContext context = context("kis")) {
            context.refresh();
            assertTrue(context.getBean(CurrentPriceProvider.class) instanceof KisCurrentPriceProvider);
            assertTrue(context.getBean(InvestorFlowProvider.class) instanceof KisInvestorFlowProvider);
        }
    }

    @Test
    public void localPlaceholderSettingAndEnvironmentOverrideSelectAtStartup() {
        StubProvider stub = new StubProvider();
        try (AnnotationConfigApplicationContext context = context(null, "alternate")) {
            context.registerBean("alternateRegistration", MarketDataProviderRegistration.class, stub::registration);
            context.refresh();
            assertEquals("alternate", context.getBean(MarketDataProviderRegistry.class).getSelectedId());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("changed",
                    Map.of("marketdata.provider", "kis")));
            assertEquals("alternate", context.getBean(MarketDataProviderRegistry.class).getSelectedId());
        }
        try (AnnotationConfigApplicationContext context = context("kis", "alternate")) {
            context.registerBean("alternateRegistration", MarketDataProviderRegistration.class, stub::registration);
            context.refresh();
            assertEquals("kis", context.getBean(MarketDataProviderRegistry.class).getSelectedId());
            assertTrue(context.getBean(CurrentPriceProvider.class) instanceof KisCurrentPriceProvider);
        }
    }

    @Test
    public void selectedAlternateReceivesAllNineOperationsAndOriginalArguments() {
        StubProvider stub = new StubProvider();
        try (AnnotationConfigApplicationContext context = alternateContext(stub)) {
            context.refresh();
            CurrentPriceProvider current = context.getBean(CurrentPriceProvider.class);
            assertEquals(Integer.valueOf(71000), current.getCurrentPrice("005930"));
            List<String> codes = List.of("005930", "000660");
            assertSame(stub.results.get("getCurrentPrices"), current.getCurrentPrices(codes));
            PeriodChartRequest period = new PeriodChartRequest("005930", "D", LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 10, 22));
            assertSame(stub.results.get("getPeriodChart"), context.getBean(PeriodChartProvider.class).getPeriodChart(period));
            MinuteChartRequest minute = new MinuteChartRequest("005930", LocalDate.of(2026, 10, 22));
            assertSame(stub.results.get("getDailyMinutes"), context.getBean(MinuteChartProvider.class).getDailyMinutes(minute));
            assertSame(stub.results.get("getRecentMinutes"), context.getBean(MinuteChartProvider.class).getRecentMinutes("005930"));
            MarketIndexChartRequest index = new MarketIndexChartRequest("0001", LocalDate.of(2026, 10, 22));
            assertSame(stub.results.get("getMarketIndexChart"), context.getBean(MarketIndexChartProvider.class).getMarketIndexChart(index));
            assertSame(stub.results.get("getTradingValueRanks"), context.getBean(StockRankingProvider.class).getTradingValueRanks());
            assertSame(stub.results.get("getVolumeRanks"), context.getBean(StockRankingProvider.class).getVolumeRanks());
            assertSame(stub.results.get("getInvestorFlow"), context.getBean(InvestorFlowProvider.class).getInvestorFlow("005930"));
            assertEquals(9, stub.calls.size());
            assertSame(codes, stub.calls.get("getCurrentPrices")[0]);
            assertSame(period, stub.calls.get("getPeriodChart")[0]);
            assertSame(minute, stub.calls.get("getDailyMinutes")[0]);
            assertSame(index, stub.calls.get("getMarketIndexChart")[0]);
            for (String operation : List.of("getCurrentPrice", "getRecentMinutes", "getInvestorFlow")) {
                assertEquals("005930", stub.calls.get(operation)[0]);
            }
        }
    }

    @Test
    public void selectedNullEmptyAndExceptionResultsRemainUnchangedWithoutFallback() {
        StubProvider stub = new StubProvider();
        stub.results.put("getCurrentPrice", null);
        stub.results.put("getRecentMinutes", null);
        stub.results.put("getPeriodChart", List.of());
        RuntimeException failure = new RuntimeException("synthetic failure");
        stub.results.put("getInvestorFlow", failure);
        try (AnnotationConfigApplicationContext context = alternateContext(stub)) {
            context.refresh();
            assertNull(context.getBean(CurrentPriceProvider.class).getCurrentPrice("005930"));
            assertNull(context.getBean(MinuteChartProvider.class).getRecentMinutes("005930"));
            assertSame(stub.results.get("getPeriodChart"), context.getBean(PeriodChartProvider.class).getPeriodChart(null));
            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> context.getBean(InvestorFlowProvider.class).getInvestorFlow("005930")));
            assertEquals(4, stub.calls.size());
        }
    }

    @Test
    public void parentSelectionIsInjectedIntoChildConsumers() {
        StubProvider stub = new StubProvider();
        try (AnnotationConfigApplicationContext parent = alternateContext(stub)) {
            parent.refresh();
            try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
                child.setParent(parent);
                child.register(Consumer.class);
                child.refresh();
                Consumer consumer = child.getBean(Consumer.class);
                assertSame(parent.getBean(CurrentPriceProvider.class), consumer.current);
                assertSame(parent.getBean(PeriodChartProvider.class), consumer.period);
                assertSame(parent.getBean(MinuteChartProvider.class), consumer.minute);
                assertSame(parent.getBean(MarketIndexChartProvider.class), consumer.index);
                assertSame(parent.getBean(StockRankingProvider.class), consumer.ranking);
                assertSame(parent.getBean(InvestorFlowProvider.class), consumer.flow);
                assertEquals("GREEN", parent.getBean(InvestorSignalService.class).getSignalColor("005930"));
            }
        }
    }

    @Test
    public void invalidConfigurationAndIncompleteSupportFailDuringRefresh() {
        for (String selected : List.of("", "unknown", "KIS", "kiwoom")) {
            try (AnnotationConfigApplicationContext context = context(selected)) {
                assertThrows(BeansException.class, context::refresh);
            }
        }
        try (AnnotationConfigApplicationContext context = context("partial")) {
            context.registerBean("partialRegistration", MarketDataProviderRegistration.class,
                    () -> MarketDataProviderRegistration.builder("partial")
                            .add(InvestorFlowProvider.class, code -> null).build());
            BeansException failure = assertThrows(BeansException.class, context::refresh);
            assertTrue(failure.getMostSpecificCause() instanceof UnsupportedOperationException);
        }
    }

    @Test
    public void duplicateProviderIdsFailDuringRefresh() {
        try (AnnotationConfigApplicationContext context = context("kis")) {
            context.registerBean("duplicateRegistration", MarketDataProviderRegistration.class,
                    () -> MarketDataProviderRegistration.builder("kis").build());
            BeansException failure = assertThrows(BeansException.class, context::refresh);
            assertTrue(failure.getMostSpecificCause().getMessage().contains("Duplicate"));
        }
    }

    @Test
    public void routedProviderKeepsHttpJsonAndRateLimitResponse() throws Exception {
        StubProvider stub = new StubProvider();
        try (AnnotationConfigApplicationContext context = alternateContext(stub)) {
            context.refresh();
            KisController controller = new KisController(context.getBean(InvestorSignalService.class), null,
                    context.getBean(PeriodChartProvider.class), context.getBean(MinuteChartProvider.class),
                    context.getBean(MarketIndexChartProvider.class), context.getBean(StockRankingProvider.class));
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
            assertEquals("{\"signalColor\":\"GREEN\"}", mvc.perform(get("/api/kis/foreigner-organization/005930"))
                    .andReturn().getResponse().getContentAsString());
            stub.results.put("getInvestorFlow", new RateLimitExceededException(2_000_000_000L));
            org.springframework.mock.web.MockHttpServletResponse response = mvc
                    .perform(get("/api/kis/foreigner-organization/005930")).andReturn().getResponse();
            assertEquals(429, response.getStatus());
            assertEquals("2", response.getHeader("Retry-After"));
        }
    }

    private AnnotationConfigApplicationContext context(String selected) {
        return context(selected, null);
    }

    private AnnotationConfigApplicationContext context(String selected, String localSelected) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        // 개인 JVM/환경 설정과 properties 파일을 읽지 않습니다.
        context.getEnvironment().getPropertySources().remove("systemProperties");
        context.getEnvironment().getPropertySources().remove("systemEnvironment");
        if (selected != null) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic",
                    Map.of("marketdata.provider", selected)));
        }
        context.registerBean(PropertySourcesPlaceholderConfigurer.class, () -> {
            PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
            if (localSelected != null) {
                Properties properties = new Properties();
                properties.setProperty("marketdata.provider", localSelected);
                configurer.setProperties(properties);
            }
            return configurer;
        });
        // 의존성이 없는 서비스는 생성만 합니다. 잘못된 KIS 호출은 외부 접속 전에 실패합니다.
        context.registerBean(KisApiService.class, () -> new KisApiService(null, null, null, null, null,
                null, null, null, null, null));
        context.register(KisMarketDataProviderConfig.class);
        context.register(ADAPTERS.toArray(new Class<?>[0]));
        context.scan("com.antmillion.marketdata");
        return context;
    }

    private AnnotationConfigApplicationContext alternateContext(StubProvider stub) {
        AnnotationConfigApplicationContext context = context("alternate");
        context.registerBean("alternateRegistration", MarketDataProviderRegistration.class, stub::registration);
        return context;
    }

    public static class Consumer {
        @Autowired CurrentPriceProvider current;
        @Autowired PeriodChartProvider period;
        @Autowired MinuteChartProvider minute;
        @Autowired MarketIndexChartProvider index;
        @Autowired StockRankingProvider ranking;
        @Autowired InvestorFlowProvider flow;
    }

    private static class StubProvider {
        private final Map<String, Object> results = new LinkedHashMap<>();
        private final Map<String, Object[]> calls = new LinkedHashMap<>();

        StubProvider() {
            results.put("getCurrentPrice", 71000);
            results.put("getCurrentPrices", new ArrayList<>(List.of(new CurrentQuote("005930", "071000", null))));
            results.put("getPeriodChart", new ArrayList<>());
            results.put("getDailyMinutes", new ArrayList<>());
            results.put("getRecentMinutes", new ArrayList<>());
            results.put("getMarketIndexChart", new ArrayList<>());
            results.put("getTradingValueRanks", new ArrayList<>());
            results.put("getVolumeRanks", new ArrayList<>());
            results.put("getInvestorFlow", new InvestorFlow("1", "2", null));
        }

        MarketDataProviderRegistration registration() {
            MarketDataProviderRegistration.Builder builder = MarketDataProviderRegistration.builder("alternate");
            for (Class<?> contract : CONTRACTS) {
                add(builder, contract);
            }
            return builder.build();
        }

        private <T> void add(MarketDataProviderRegistration.Builder builder, Class<T> contract) {
            T provider = contract.cast(Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                            if (method.getName().equals("equals")) return proxy == args[0];
                            return "synthetic-" + contract.getSimpleName();
                        }
                        calls.put(method.getName(), args);
                        Object result = results.get(method.getName());
                        if (result instanceof RuntimeException) throw (RuntimeException) result;
                        return result;
                    }));
            builder.add(contract, provider);
        }
    }
}
