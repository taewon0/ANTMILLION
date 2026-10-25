package com.antmillion.kiwoom;

import com.antmillion.config.GlobalExceptionHandler;
import com.antmillion.config.RestTemplateConfig;
import com.antmillion.kis.config.KisMarketDataProviderConfig;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.service.*;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.service.*;
import com.antmillion.marketdata.config.MarketDataRoutingConfig;
import com.antmillion.marketdata.config.RealtimeRoutingConfig;
import com.antmillion.marketdata.dto.*;
import com.antmillion.marketdata.provider.*;
import com.antmillion.marketdata.service.InvestorSignalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.TimeMeter;
import org.junit.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 실제 등록/선택/adapter를 연결하되 개인 properties·DB·외부 Redis/HTTP/WS는 사용하지 않습니다. */
public class KiwoomMarketDataRoutingTest {
    private static final List<Class<?>> CONTRACTS = List.of(CurrentPriceProvider.class, PeriodChartProvider.class,
            MinuteChartProvider.class, MarketIndexChartProvider.class, StockRankingProvider.class, InvestorFlowProvider.class);
    private static final List<Class<?>> ADAPTERS = List.of(KiwoomCurrentPriceProvider.class, KiwoomPeriodChartProvider.class,
            KiwoomMinuteChartProvider.class, KiwoomMarketIndexChartProvider.class,
            KiwoomStockRankingProvider.class, KiwoomInvestorFlowProvider.class);
    private static final LocalDate DATE = LocalDate.of(2026, 10, 22);
    private static final String PRICE = "{\"stk_cd\":\"005930\",\"cur_prc\":\"+70000\",\"flu_rt\":\"-1.25\"}";
    private static final String OHLC = "\"cur_prc\":\"+100\",\"open_pric\":\"90\",\"high_pric\":\"110\",\"low_pric\":\"80\"";
    private static final String PERIOD = "{\"stk_cd\":\"005930\",\"stk_dt_pole_chart_qry\":[{\"dt\":\"20261022\"," + OHLC + "}]}";
    private static final String MINUTE = "{\"stk_cd\":\"005930\",\"stk_min_pole_chart_qry\":[{\"cntr_tm\":\"20261022090000\"," + OHLC + "}]}";
    private static final String INDEX = "{\"inds_cd\":\"001\",\"inds_dt_pole_qry\":[{\"dt\":\"20261022\"," + OHLC + "}]}";
    private static final String RANK = "{\"tdy_trde_qty_upper\":[{\"stk_cd\":\"005930\",\"stk_nm\":\"합성종목\","
            + "\"cur_prc\":\"+70000\",\"trde_qty\":\"1000\",\"flu_rt\":\"-1.25\"}]}";
    private static final String FLOW = "{\"opmr_invsr_trde_chart\":[{\"tm\":\"153000\",\"frgnr_invsr\":\"1\",\"orgn\":\"2\"}]}";

    @Test
    public void selectsSixActualAdaptersWithoutIoAndKeepsQualifiedHttpClient() {
        try (Fixture f = new Fixture("kiwoom", "virtual")) {
            f.start();
            MarketDataProviderRegistry registry = f.context.getBean(MarketDataProviderRegistry.class);
            assertEquals("kiwoom", registry.getSelectedId());
            assertEquals(Set.copyOf(CONTRACTS), registry.getSupportedContracts());
            for (int i = 0; i < CONTRACTS.size(); i++) {
                assertSame(f.context.getBean(ADAPTERS.get(i)), f.context.getBean(CONTRACTS.get(i)));
                assertEquals(3, f.context.getBeansOfType(CONTRACTS.get(i)).size());
            }
            assertSame(f.kisRest, f.context.getBean(RestTemplate.class));
            assertSame(f.rest, f.context.getBean("kiwoomAuthRestTemplate"));
            assertTrue(f.redis.reads.isEmpty());
            assertTrue(f.redis.values.isEmpty());
            assertTrue(f.locks.keys.isEmpty());
        }
    }

    @Test
    public void optionalFileRegistersAndSelectsKiwoomWithoutIo() {
        String file = "kiwoom.auth.enabled=true\nkiwoom.app-key=synthetic-key\n"
                + "kiwoom.app-secret=synthetic-secret\nmarketdata.provider=kiwoom\n";
        try (Fixture f = new Fixture(new HashMap<>(), file)) {
            f.start();
            assertEquals("kiwoom", f.context.getBean(MarketDataProviderRegistry.class).getSelectedId());
            assertTrue(f.redis.reads.isEmpty());
            assertTrue(f.locks.keys.isEmpty());
        }
    }

    @Test
    public void environmentOverridesFileAndSelectionIsFixedUntilRestart() {
        Map<String, Object> props = KiwoomAuthConfigTest.enabled();
        props.put("marketdata.provider", "kis");
        try (Fixture f = new Fixture(props, "marketdata.provider=kiwoom\n")) {
            f.start();
            assertTrue(f.context.getBean(CurrentPriceProvider.class) instanceof KisCurrentPriceProvider);
            f.context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("changed",
                    Map.of("marketdata.provider", "kiwoom")));
            assertEquals("kis", f.context.getBean(MarketDataProviderRegistry.class).getSelectedId());
            assertTrue(f.context.getBean(CurrentPriceProvider.class) instanceof KisCurrentPriceProvider);
            assertTrue(f.redis.reads.isEmpty());
        }
    }

    @Test
    public void disabledAndUnknownSelectionsFailStartupBeforeIo() {
        for (String selected : List.of("kiwoom", "missing", "", "KIWOOM", " kiwoom ")) {
            Map<String, Object> props = new HashMap<>();
            props.put("marketdata.provider", selected);
            props.put("kiwoom.auth.enabled", "false");
            try (Fixture f = new Fixture(props, null)) {
                assertThrows(BeansException.class, f::start);
                assertTrue(f.redis.reads.isEmpty());
                assertTrue(f.locks.keys.isEmpty());
            }
        }
    }

    @Test
    public void firstSelectedQueryIssuesAndReusesScopedTokenThroughConfiguredInfrastructure() {
        try (Fixture f = new Fixture("kiwoom", "virtual")) {
            f.start();
            assertTrue(f.redis.reads.isEmpty());
            f.server.expect(requestTo(f.base() + "/oauth2/token")).andExpect(method(HttpMethod.POST))
                    .andRespond(withSuccess("{\"token\":\"synthetic-token\",\"token_type\":\"bearer\","
                            + "\"expires_dt\":\"20990101000000\"}", MediaType.APPLICATION_JSON));
            f.expect("ka10001", "/api/dostk/stkinfo", PRICE);
            f.expect("ka10001", "/api/dostk/stkinfo", PRICE);
            assertEquals(Integer.valueOf(70000),
                    f.context.getBean(CurrentPriceProvider.class).getCurrentPrice("005930"));
            f.refill();
            assertEquals(Integer.valueOf(70000),
                    f.context.getBean(CurrentPriceProvider.class).getCurrentPrice("005930"));
            assertEquals(1, f.locks.unlocks.get());
            assertEquals(List.of(f.scope().getLockKey()), f.locks.keys);
            assertTrue(f.redis.values.containsKey(f.scope().getTokenKey()));
        }
    }

    @Test
    public void selectedCurrentPriceUsesConfiguredSharedCircuitAndRejectsBeforeTokenIo() {
        Map<String, Object> props = Fixture.properties("kiwoom", "virtual");
        props.put("marketdata.circuit-breaker.minimum-calls", "2");
        try (Fixture f = new Fixture(props, null)) {
            f.start();
            CurrentPriceProvider prices = f.context.getBean(CurrentPriceProvider.class);
            assertSame(f.context.getBean(com.antmillion.marketdata.resilience.MarketDataCircuitBreakers.class),
                    org.springframework.test.util.ReflectionTestUtils.getField(prices, "circuitBreakers"));
            f.server.expect(requestTo(f.base() + "/oauth2/token")).andRespond(withSuccess(
                    "{\"token\":\"synthetic-token\",\"token_type\":\"bearer\",\"expires_dt\":\"20990101000000\"}",
                    MediaType.APPLICATION_JSON));
            for (int i = 0; i < 2; i++) f.server.expect(requestTo(f.base() + "/api/dostk/stkinfo"))
                    .andRespond(withServerError());
            assertThrows(com.antmillion.kiwoom.exception.KiwoomMarketDataException.class,
                    () -> prices.getCurrentPrice("005930"));
            f.refill();
            assertThrows(com.antmillion.kiwoom.exception.KiwoomMarketDataException.class,
                    () -> prices.getCurrentPrice("005930"));
            int reads = f.redis.reads.size();
            assertThrows(com.antmillion.marketdata.exception.MarketDataUnavailableException.class,
                    () -> prices.getCurrentPrice("005930"));
            assertTrue(prices.getCurrentPrices(List.of("005930", "000660")).isEmpty());
            assertEquals(reads, f.redis.reads.size());
            assertEquals(1, f.locks.unlocks.get());
        }
    }

    @Test
    public void selectedPeriodChartSharesConfiguredCircuitWithCurrentPrice() {
        Map<String, Object> props = Fixture.properties("kiwoom", "virtual");
        props.put("marketdata.circuit-breaker.minimum-calls", "2");
        try (Fixture f = new Fixture(props, null)) {
            f.start();
            CurrentPriceProvider prices = f.context.getBean(CurrentPriceProvider.class);
            PeriodChartProvider charts = f.context.getBean(PeriodChartProvider.class);
            Object shared = f.context.getBean(com.antmillion.marketdata.resilience.MarketDataCircuitBreakers.class);
            assertSame(shared, org.springframework.test.util.ReflectionTestUtils.getField(prices, "circuitBreakers"));
            assertSame(shared, org.springframework.test.util.ReflectionTestUtils.getField(charts, "circuitBreakers"));
            f.server.expect(requestTo(f.base() + "/oauth2/token")).andRespond(withSuccess(
                    "{\"token\":\"synthetic-token\",\"token_type\":\"bearer\",\"expires_dt\":\"20990101000000\"}",
                    MediaType.APPLICATION_JSON));
            f.server.expect(requestTo(f.base() + "/api/dostk/stkinfo")).andRespond(withServerError());
            f.server.expect(requestTo(f.base() + "/api/dostk/chart")).andRespond(withServerError());
            assertThrows(com.antmillion.kiwoom.exception.KiwoomMarketDataException.class,
                    () -> prices.getCurrentPrice("005930"));
            f.refill();
            PeriodChartRequest request = new PeriodChartRequest("005930", "D", DATE.minusDays(10), DATE);
            assertThrows(com.antmillion.kiwoom.exception.KiwoomMarketDataException.class,
                    () -> charts.getPeriodChart(request));
            assertThrows(com.antmillion.marketdata.exception.MarketDataUnavailableException.class,
                    () -> charts.getPeriodChart(request));
            assertThrows(com.antmillion.marketdata.exception.MarketDataUnavailableException.class,
                    () -> prices.getCurrentPrice("005930"));
            assertEquals(1, f.locks.unlocks.get());
        }
    }

    @Test
    public void allNineOperationsUseRegisteredAdaptersAndDedicatedHttp() {
        try (Fixture f = new Fixture("kiwoom", "virtual")) {
            f.start();
            f.seedToken();
            f.expect("ka10001", "/api/dostk/stkinfo", PRICE);
            f.expect("ka10001", "/api/dostk/stkinfo", PRICE);
            f.expect("ka10081", "/api/dostk/chart", PERIOD);
            f.expect("ka10080", "/api/dostk/chart", MINUTE);
            f.expect("ka10080", "/api/dostk/chart", "{\"stk_min_pole_chart_qry\":[]}");
            f.expect("ka20006", "/api/dostk/chart", INDEX);
            f.expect("ka10030", "/api/dostk/rkinfo", RANK);
            f.expect("ka10030", "/api/dostk/rkinfo", RANK);
            f.expect("ka10064", "/api/dostk/chart", FLOW);
            CurrentPriceProvider current = f.context.getBean(CurrentPriceProvider.class);
            assertEquals(Integer.valueOf(70000), current.getCurrentPrice("005930"));
            f.refill();
            assertEquals(new CurrentQuote("005930", "70000", "-1.25"), current.getCurrentPrices(List.of("005930")).get(0));
            assertEquals("100", f.context.getBean(PeriodChartProvider.class).getPeriodChart(period()).get(0).getClosePrice());
            MinuteChartProvider minute = f.context.getBean(MinuteChartProvider.class);
            assertEquals("100", minute.getDailyMinutes(new MinuteChartRequest("005930", DATE)).get(0).getClosePrice());
            f.refill();
            assertTrue(minute.getRecentMinutes("005930").isEmpty());
            assertEquals("1.00", f.context.getBean(MarketIndexChartProvider.class)
                    .getMarketIndexChart(new MarketIndexChartRequest("0001", DATE)).get(0).getCloseValue());
            StockRankingProvider ranking = f.context.getBean(StockRankingProvider.class);
            assertEquals("005930", ranking.getTradingValueRanks().get(0).getStockCode());
            f.refill();
            assertEquals("005930", ranking.getVolumeRanks().get(0).getStockCode());
            assertEquals(new InvestorFlow("1", "2", "3"),
                    f.context.getBean(InvestorFlowProvider.class).getInvestorFlow("005930"));
            assertTrue(f.locks.keys.isEmpty());
        }
    }

    @Test
    public void mvcColdAndWarmResponsesKeepExistingPathsAndJson() throws Exception {
        List<String> paths = List.of("/periodChart/005930?period=D", "/stream/005930", "/candle/005930",
                "/marketIndex/0001", "/volumeRank", "/volumeRank/paged?page=1&size=10",
                "/foreigner-organization/005930");
        String[] apis = {"ka10081", "ka10080", "ka10080", "ka20006", "ka10030", "ka10030", "ka10064"};
        String kstYesterday = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1)
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String today = LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String[] bodies = {PERIOD.replace("20261022", kstYesterday), MINUTE.replace("20261022", today),
                "{\"stk_min_pole_chart_qry\":[]}", INDEX.replace("20261022", kstYesterday), RANK, RANK, FLOW};
        for (int i = 0; i < paths.size(); i++) {
            try (Fixture f = new Fixture("kiwoom", "virtual")) {
                f.start();
                f.seedToken();
                f.expect(apis[i], endpoint(apis[i]), bodies[i]);
                if (i == 2) f.expect(apis[i], endpoint(apis[i]), bodies[i]);
                MockMvc mvc = f.mvc();
                JsonNode cold = f.json(mvc, paths.get(i));
                f.refill();
                assertEquals(cold, f.json(mvc, paths.get(i)));
                if (i == 0) assertEquals("100", cold.get(0).path("stck_clpr").asText());
                if (i == 1) assertEquals("100", cold.get(0).path("stck_prpr").asText());
                if (i == 3) assertEquals("1.00", cold.get(0).path("bstp_nmix_prpr").asText());
                if (i == 4) assertEquals("005930", cold.get(0).path("mksc_shrn_iscd").asText());
                if (i == 5) assertEquals(1, cold.path("totalItems").asInt());
                if (i == 6) assertEquals("GREEN", cold.path("signalColor").asText());
            }
        }
    }

    @Test
    public void currentPriceCacheFallbackAndListPartialFailureRemainAvailable() {
        try (Fixture f = new Fixture("kiwoom", "virtual")) {
            f.start();
            f.seedToken();
            f.expect("ka10001", "/api/dostk/stkinfo", PRICE);
            f.expect("ka10001", "/api/dostk/stkinfo", PRICE);
            CurrentPriceProvider current = f.context.getBean(CurrentPriceProvider.class);
            assertEquals(Integer.valueOf(70000), current.getCurrentPrice("005930"));
            assertEquals(Integer.valueOf(70000), current.getCurrentPrice("005930"));
            assertThrows(KiwoomRateLimitExceededException.class, () -> current.getCurrentPrice("000660"));
            f.refill();
            List<CurrentQuote> quotes = current.getCurrentPrices(List.of("005930", "000660"));
            assertEquals(List.of(new CurrentQuote("005930", "70000", "-1.25")), quotes);
            assertEquals(quotes, current.getCurrentPrices(List.of("005930", "000660")));
        }
    }

    @Test
    public void allSixAdaptersShareRealBudgetAndRejectBeforeTokenOrHttp() throws Exception {
        try (Fixture f = new Fixture("kiwoom", "real")) {
            f.start();
            f.seedToken();
            f.expect("ka10001", "/api/dostk/stkinfo", PRICE);
            f.expect("ka10081", "/api/dostk/chart", PERIOD);
            f.expect("ka10080", "/api/dostk/chart", MINUTE);
            f.expect("ka20006", "/api/dostk/chart", INDEX);
            f.expect("ka10030", "/api/dostk/rkinfo", RANK);
            f.context.getBean(CurrentPriceProvider.class).getCurrentPrice("005930");
            f.context.getBean(PeriodChartProvider.class).getPeriodChart(period());
            f.context.getBean(MinuteChartProvider.class).getDailyMinutes(new MinuteChartRequest("005930", DATE));
            f.context.getBean(MarketIndexChartProvider.class).getMarketIndexChart(new MarketIndexChartRequest("0001", DATE));
            f.context.getBean(StockRankingProvider.class).getTradingValueRanks();
            String tokenKey = f.scope().getTokenKey();
            long tokenReads = f.redis.reads.stream().filter(tokenKey::equals).count();
            org.springframework.mock.web.MockHttpServletResponse response = f.mvc()
                    .perform(get("/api/kis/foreigner-organization/005930")).andReturn().getResponse();
            assertEquals(429, response.getStatus());
            assertEquals("1", response.getHeader("Retry-After"));
            JsonNode json = new ObjectMapper().readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertEquals(Set.of("error", "message", "retryAfter"), fields(json));
            assertEquals("Rate limit exceeded", json.path("error").asText());
            assertTrue(json.path("message").asText().contains("키움증권"));
            assertEquals(1, json.path("retryAfter").asInt());
            assertEquals(tokenReads, f.redis.reads.stream().filter(tokenKey::equals).count());
            // 캐시 적중은 소진된 공유 예산과 무관하며, 미적중은 KIS로 전환하지 않습니다.
            assertEquals(1, f.context.getBean(PeriodChartProvider.class).getPeriodChart(period()).size());
        }
    }

    @Test
    public void remoteFailureDoesNotFallbackOrExposeRemoteBodyAndConsumesBudget() {
        try (Fixture f = new Fixture("kiwoom", "virtual")) {
            f.start();
            f.seedToken();
            f.server.expect(requestTo(f.base() + "/api/dostk/chart"))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body("synthetic-sensitive-remote").contentType(MediaType.TEXT_PLAIN));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> f.context.getBean(InvestorFlowProvider.class).getInvestorFlow("005930"));
            assertFalse(failure.getMessage().contains("synthetic-sensitive"));
            assertNull(failure.getCause());
            assertThrows(KiwoomRateLimitExceededException.class,
                    () -> f.context.getBean(InvestorFlowProvider.class).getInvestorFlow("005930"));
            assertEquals(1, f.redis.values.size()); // 사전 주입한 토큰만 남습니다.
        }
    }

    @Test
    public void restKiwoomKeepsIndependentKisRealtimeSelection() {
        try (Fixture f = new Fixture("kiwoom", "virtual")) {
            f.context.register(RealtimeRoutingConfig.class);
            RealtimeSubscriptionProvider provider = realtime();
            f.context.registerBean("kisRealtime", RealtimeProviderRegistration.class,
                    () -> new RealtimeProviderRegistration("kis", provider, Set.of(RealtimeSubscriptionProvider.Kind.values())));
            f.start();
            assertEquals("kiwoom", f.context.getBean(MarketDataProviderRegistry.class).getSelectedId());
            assertEquals("kis", f.context.getBean(RealtimeProviderRegistry.class).getSelectedId());
            assertSame(provider, f.context.getBean(RealtimeSubscriptionProvider.class));
        }
    }

    @Test
    public void unsupportedKiwoomRealtimeSelectionFailsStartupWithoutIo() {
        try (Fixture f = new Fixture("kiwoom", "virtual")) {
            f.context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ws",
                    Map.of("marketdata.realtime-provider", "kiwoom")));
            f.context.register(RealtimeRoutingConfig.class);
            f.context.registerBean("kisRealtime", RealtimeProviderRegistration.class,
                    () -> new RealtimeProviderRegistration("kis", realtime(), Set.of(RealtimeSubscriptionProvider.Kind.values())));
            assertThrows(BeansException.class, f::start);
            assertTrue(f.redis.reads.isEmpty());
            assertTrue(f.locks.keys.isEmpty());
        }
    }

    private static RealtimeSubscriptionProvider realtime() {
        return new RealtimeSubscriptionProvider() {
            @Override public void subscribe(String code, Kind kind) { throw new AssertionError("구독 미요청"); }
            @Override public void unsubscribe(String code, Kind kind) { throw new AssertionError("해제 미요청"); }
        };
    }

    private static Set<String> fields(JsonNode json) {
        Set<String> names = new java.util.HashSet<>();
        json.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static PeriodChartRequest period() { return new PeriodChartRequest("005930", "D", DATE.minusDays(100), DATE); }
    private static String endpoint(String api) {
        if ("ka10001".equals(api)) return "/api/dostk/stkinfo";
        if ("ka10030".equals(api)) return "/api/dostk/rkinfo";
        return "/api/dostk/chart";
    }

    private static class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context;
        final RestTemplate rest = new RestTemplate();
        final RestTemplate kisRest = new RestTemplate();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        final MockRestServiceServer kisServer = MockRestServiceServer.bindTo(kisRest).build();
        final KiwoomTokenServiceTest.MemoryRedis redis = new KiwoomTokenServiceTest.MemoryRedis(Clock.systemUTC());
        final KiwoomTokenServiceTest.LockClient locks = new KiwoomTokenServiceTest.LockClient();
        final AtomicLong nanos = new AtomicLong();
        final Map<String, Object> props;

        Fixture(String selected, String mode) {
            this(properties(selected, mode), null);
        }

        Fixture(Map<String, Object> props, String file) {
            this.props = props;
            context = KiwoomAuthConfigTest.context(props, file);
            KiwoomAuthConfigTest.infrastructure(context, redis, locks, rest);
            context.register(KisMarketDataProviderConfig.class, MarketDataRoutingConfig.class, RestTemplateConfig.class,
                    KisCurrentPriceProvider.class, KisPeriodChartProvider.class, KisMinuteChartProvider.class,
                    KisMarketIndexChartProvider.class, KisStockRankingProvider.class, KisInvestorFlowProvider.class,
                    InvestorSignalService.class);
            context.registerBean(KisApiService.class, () -> new KisApiService(null, null, null, null, null,
                    null, null, null, null, null, com.antmillion.marketdata.CircuitBreakerTestSupport.defaults()));
            // 전용 Kiwoom HTTP와 KIS 기본 HTTP 모두 네트워크를 사용하지 못하게 고정합니다.
            context.addBeanFactoryPostProcessor(factory -> {
                ((AbstractBeanDefinition) factory.getBeanDefinition("kisRestTemplate")).setInstanceSupplier(() -> kisRest);
                ((AbstractBeanDefinition) factory.getBeanDefinition("kiwoomRestRateLimiter")).setInstanceSupplier(
                        () -> KiwoomRestRateLimitTest.withClock(new TimeMeter() {
                            @Override public long currentTimeNanos() { return nanos.get(); }
                            @Override public boolean isWallClockBased() { return false; }
                        }));
            });
        }

        void start() { context.refresh(); }
        String base() { return context.getBean(KiwoomTokenService.class).getBaseUrl(); }
        KiwoomAccessTokenScope scope() {
            return new KiwoomAccessTokenScope(props.getOrDefault("kiwoom.mode", "virtual").toString(),
                    "synthetic-key", "synthetic-secret");
        }
        void seedToken() {
            context.getBean(KiwoomAccessTokenRedisRepository.class).save(scope(), "synthetic-token",
                    Instant.now().plusSeconds(200_000), 600);
        }
        void refill() { nanos.addAndGet(1_000_000_000L); }
        void expect(String api, String path, String body) {
            server.expect(requestTo(base() + path)).andExpect(method(HttpMethod.POST))
                    .andExpect(header("api-id", api)).andExpect(header("Authorization", "Bearer synthetic-token"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        MockMvc mvc() {
            KisController controller = new KisController(context.getBean(InvestorSignalService.class), null,
                    context.getBean(PeriodChartProvider.class), context.getBean(MinuteChartProvider.class),
                    context.getBean(MarketIndexChartProvider.class), context.getBean(StockRankingProvider.class));
            return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        }
        JsonNode json(MockMvc mvc, String path) throws Exception {
            org.springframework.mock.web.MockHttpServletResponse response = mvc.perform(get("/api/kis" + path))
                    .andReturn().getResponse();
            assertEquals(response.getContentAsString(), 200, response.getStatus());
            return new ObjectMapper().readTree(response.getContentAsString(StandardCharsets.UTF_8));
        }
        @Override public void close() {
            context.close();
            server.verify();
            kisServer.verify();
        }
        private static Map<String, Object> properties(String selected, String mode) {
            Map<String, Object> props = KiwoomAuthConfigTest.enabled();
            props.put("marketdata.provider", selected);
            props.put("kiwoom.mode", mode);
            return props;
        }
    }
}
