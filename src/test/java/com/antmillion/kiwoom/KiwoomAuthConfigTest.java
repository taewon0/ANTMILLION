package com.antmillion.kiwoom;

import com.antmillion.config.RestTemplateConfig;
import com.antmillion.kiwoom.config.KiwoomAuthConfig;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import com.antmillion.kiwoom.service.KiwoomTokenService;
import com.antmillion.kiwoom.service.KiwoomCurrentPriceProvider;
import org.junit.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 운영 component scan과 동일한 설정을 합성 환경·클래스패스로만 읽습니다. */
public class KiwoomAuthConfigTest {
    @Test
    public void missingOptionalFileAndCredentialsRegisterNoAuthInfrastructure() {
        try (AnnotationConfigApplicationContext context = context(Map.of(), null)) {
            context.refresh();
            assertTrue(context.getBeansOfType(KiwoomTokenService.class).isEmpty());
            assertTrue(context.getBeansOfType(KiwoomAccessTokenRedisRepository.class).isEmpty());
            assertTrue(context.getBeansOfType(RestTemplate.class).isEmpty());
            assertTrue(context.getBeansOfType(KiwoomCurrentPriceProvider.class).isEmpty());
        }
    }

    @Test
    public void explicitDisabledIgnoresUnusedCredentialsModeAndBuffer() {
        try (AnnotationConfigApplicationContext context = context(Map.of("kiwoom.auth.enabled", "false",
                "kiwoom.mode", "bad", "kiwoom.auth.refresh-buffer-seconds", "bad"), null)) {
            context.refresh();
            assertTrue(context.getBeansOfType(KiwoomTokenService.class).isEmpty());
        }
    }

    @Test
    public void optionalFileEnablesAuthButDoesNotReadRedisOrAcquireLockAtStartup() {
        try (AnnotationConfigApplicationContext context = context(Map.of(),
                "kiwoom.auth.enabled=true\nkiwoom.app-key=synthetic-key\nkiwoom.app-secret=synthetic-secret\n"
                        + "kiwoom.rest.rate-limit.real-per-second=2\n")) {
            KiwoomTokenServiceTest.MutableClock clock = new KiwoomTokenServiceTest.MutableClock();
            KiwoomTokenServiceTest.MemoryRedis redis = new KiwoomTokenServiceTest.MemoryRedis(clock);
            KiwoomTokenServiceTest.LockClient locks = new KiwoomTokenServiceTest.LockClient();
            RestTemplate rest = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
            infrastructure(context, redis, locks, rest);
            context.refresh();
            assertNotNull(context.getBean(KiwoomTokenService.class));
            assertEquals(2, context.getBean(com.antmillion.kiwoom.config.KiwoomRestRateLimitConfig.class)
                    .tokensPerSecond("https://api.kiwoom.com"));
            assertEquals(1, context.getBeansOfType(com.antmillion.kiwoom.service.KiwoomRestRateLimiter.class).size());
            assertEquals(1, context.getBeansOfType(KiwoomCurrentPriceProvider.class).size());
            assertTrue(redis.reads.isEmpty());
            assertTrue(locks.keys.isEmpty());
            server.verify();
        }
    }

    @Test
    public void environmentOverridesFileIncludingDisabledFlag() {
        try (AnnotationConfigApplicationContext context = context(Map.of("kiwoom.auth.enabled", "false"),
                "kiwoom.auth.enabled=true\nkiwoom.mode=bad\n")) {
            context.refresh();
            assertTrue(context.getBeansOfType(KiwoomTokenService.class).isEmpty());
        }
    }

    @Test
    public void invalidEnabledValueIsRejected() {
        try (AnnotationConfigApplicationContext context = context(Map.of("kiwoom.auth.enabled", "tru"), null)) {
            assertThrows(IllegalArgumentException.class, context::refresh);
        }
    }

    @Test
    public void disabledAuthKeepsAllSixActualKisAdaptersSelectable() {
        try (AnnotationConfigApplicationContext context = context(Map.of(), null)) {
            context.registerBean(com.antmillion.kis.service.KisApiService.class,
                    () -> new com.antmillion.kis.service.KisApiService(null, null, null, null, null,
                            null, null, null, null, null));
            context.register(com.antmillion.kis.config.KisMarketDataProviderConfig.class,
                    com.antmillion.marketdata.config.MarketDataRoutingConfig.class,
                    com.antmillion.kis.service.KisCurrentPriceProvider.class,
                    com.antmillion.kis.service.KisPeriodChartProvider.class,
                    com.antmillion.kis.service.KisMinuteChartProvider.class,
                    com.antmillion.kis.service.KisMarketIndexChartProvider.class,
                    com.antmillion.kis.service.KisStockRankingProvider.class,
                    com.antmillion.kis.service.KisInvestorFlowProvider.class);
            context.refresh();
            com.antmillion.marketdata.provider.MarketDataProviderRegistry registry =
                    context.getBean(com.antmillion.marketdata.provider.MarketDataProviderRegistry.class);
            assertEquals("kis", registry.getSelectedId());
            assertEquals(6, registry.getSupportedContracts().size());
            for (Class<?> contract : registry.getSupportedContracts()) {
                assertSame(registry.getProvider(contract), context.getBean(contract));
            }
            assertTrue(context.getBeansOfType(KiwoomTokenService.class).isEmpty());
        }
    }

    @Test
    public void enabledAuthRegistersKiwoomAdaptersWithoutReplacingDefaultKisSelection() {
        try (AnnotationConfigApplicationContext context = context(enabled(), null)) {
            KiwoomTokenServiceTest.MutableClock clock = new KiwoomTokenServiceTest.MutableClock();
            KiwoomTokenServiceTest.MemoryRedis redis = new KiwoomTokenServiceTest.MemoryRedis(clock);
            RestTemplate rest = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
            infrastructure(context, redis, new KiwoomTokenServiceTest.LockClient(), rest);
            context.registerBean(com.antmillion.kis.service.KisApiService.class,
                    () -> new com.antmillion.kis.service.KisApiService(null, null, null, null, null,
                            null, null, null, null, null));
            context.register(com.antmillion.kis.config.KisMarketDataProviderConfig.class,
                    com.antmillion.marketdata.config.MarketDataRoutingConfig.class,
                    com.antmillion.kis.service.KisCurrentPriceProvider.class,
                    com.antmillion.kis.service.KisPeriodChartProvider.class,
                    com.antmillion.kis.service.KisMinuteChartProvider.class,
                    com.antmillion.kis.service.KisMarketIndexChartProvider.class,
                    com.antmillion.kis.service.KisStockRankingProvider.class,
                    com.antmillion.kis.service.KisInvestorFlowProvider.class);
            context.refresh();
            com.antmillion.marketdata.provider.MarketDataProviderRegistry registry =
                    context.getBean(com.antmillion.marketdata.provider.MarketDataProviderRegistry.class);
            assertEquals("kis", registry.getSelectedId());
            assertEquals(6, registry.getSupportedContracts().size());
            for (Class<?> contract : registry.getSupportedContracts()) {
                assertSame(registry.getProvider(contract), context.getBean(contract));
            }
            assertEquals(1, context.getBeansOfType(KiwoomCurrentPriceProvider.class).size());
            assertEquals(1, context.getBeansOfType(com.antmillion.kiwoom.service.KiwoomPeriodChartProvider.class).size());
            assertEquals(1, context.getBeansOfType(com.antmillion.kiwoom.service.KiwoomMinuteChartProvider.class).size());
            assertEquals(1, context.getBeansOfType(com.antmillion.kiwoom.service.KiwoomMarketIndexChartProvider.class).size());
            assertEquals(1, context.getBeansOfType(com.antmillion.kiwoom.service.KiwoomStockRankingProvider.class).size());
            assertEquals(1, context.getBeansOfType(com.antmillion.kiwoom.service.KiwoomInvestorFlowProvider.class).size());
            assertEquals(1, context.getBeansOfType(com.antmillion.kiwoom.repository.KiwoomStockRankingRedisRepository.class).size());
            assertEquals(1, context.getBeansOfType(com.antmillion.kiwoom.repository.KiwoomInvestorFlowRedisRepository.class).size());
            assertTrue(redis.reads.isEmpty());
            server.verify();
        }
    }

    @Test
    public void enabledWithoutCredentialsFailsBeforeIssuance() {
        try (AnnotationConfigApplicationContext context = context(Map.of("kiwoom.auth.enabled", "true"), null)) {
            RestTemplate rest = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
            infrastructure(context, new KiwoomTokenServiceTest.MemoryRedis(new KiwoomTokenServiceTest.MutableClock()),
                    new KiwoomTokenServiceTest.LockClient(), rest);
            BeansException error = assertThrows(BeansException.class, context::refresh);
            assertTrue(error.getMostSpecificCause().getMessage().contains("자격증명"));
            server.verify();
        }
    }

    @Test
    public void enabledMissingCredentialsInvalidModesAndInvalidBuffersFailStartup() {
        for (Map<String, Object> invalid : java.util.List.of(
                Map.<String, Object>of("kiwoom.app-key", ""), Map.<String, Object>of("kiwoom.app-secret", " "),
                Map.<String, Object>of("kiwoom.mode", "bad"),
                Map.<String, Object>of("kiwoom.auth.refresh-buffer-seconds", "-1"),
                Map.<String, Object>of("kiwoom.auth.refresh-buffer-seconds", "43201"),
                Map.<String, Object>of("kiwoom.auth.refresh-buffer-seconds", "bad"))) {
            Map<String, Object> props = enabled();
            props.putAll(invalid);
            try (AnnotationConfigApplicationContext context = context(props, null)) {
                infrastructure(context, new KiwoomTokenServiceTest.MemoryRedis(new KiwoomTokenServiceTest.MutableClock()),
                        new KiwoomTokenServiceTest.LockClient(), new RestTemplate());
                assertThrows(BeansException.class, context::refresh);
            }
        }
    }

    @Test
    public void zeroAndMaximumBuffersAreAllowedWithoutIssuance() {
        for (String buffer : java.util.List.of("0", "43200")) {
            Map<String, Object> props = enabled();
            props.put("kiwoom.auth.refresh-buffer-seconds", buffer);
            try (AnnotationConfigApplicationContext context = context(props, null)) {
                RestTemplate rest = new RestTemplate();
                MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
                infrastructure(context, new KiwoomTokenServiceTest.MemoryRedis(new KiwoomTokenServiceTest.MutableClock()),
                        new KiwoomTokenServiceTest.LockClient(), rest);
                context.refresh();
                assertNotNull(context.getBean(KiwoomTokenService.class));
                server.verify();
            }
        }
    }

    @Test
    public void enabledAuthKeepsKisPrimaryClientAndUsesOwnQualifiedClient() {
        Map<String, Object> props = enabled();
        props.put("kiwoom.auth.refresh-buffer-seconds", "0");
        try (AnnotationConfigApplicationContext context = context(props, null)) {
            RestTemplate rest = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
            server.expect(requestTo("https://mockapi.kiwoom.com/oauth2/token"))
                    .andRespond(withSuccess("{\"token\":\"synthetic-token\",\"token_type\":\"bearer\","
                            + "\"expires_dt\":\"20990101000000\"}", MediaType.APPLICATION_JSON));
            infrastructure(context, new KiwoomTokenServiceTest.MemoryRedis(new KiwoomTokenServiceTest.MutableClock()),
                    new KiwoomTokenServiceTest.LockClient(), rest);
            context.register(RestTemplateConfig.class);
            context.refresh();
            assertSame(context.getBean("kisRestTemplate"), context.getBean(RestTemplate.class));
            // 기동 후 환경 Map을 바꿔도 발급·저장 범위는 기동 시 스냅샷을 사용합니다.
            props.put("kiwoom.mode", "real");
            props.put("kiwoom.app-key", "changed-key");
            assertEquals("synthetic-token", context.getBean(KiwoomTokenService.class).getAccessToken());
            server.verify();
        }
    }

    static Map<String, Object> enabled() {
        Map<String, Object> props = new HashMap<>();
        props.put("kiwoom.auth.enabled", "true");
        props.put("kiwoom.app-key", "synthetic-key");
        props.put("kiwoom.app-secret", "synthetic-secret");
        return props;
    }

    static AnnotationConfigApplicationContext context(Map<String, Object> props, String optionalFile) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().remove("systemProperties");
        context.getEnvironment().getPropertySources().remove("systemEnvironment");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic", props));
        // 실제 kiwoomApi.properties가 생겨도 테스트에서 읽지 못하게 가립니다.
        context.setClassLoader(new ClassLoader(KiwoomAuthConfigTest.class.getClassLoader()) {
            @Override public URL getResource(String name) {
                if (!"kiwoomApi.properties".equals(name)) return super.getResource(name);
                if (optionalFile == null) return null;
                try {
                    return new URL(null, "memory:kiwoomApi.properties", new URLStreamHandler() {
                        @Override protected URLConnection openConnection(URL url) {
                            return new URLConnection(url) {
                                @Override public void connect() { }
                                @Override public InputStream getInputStream() {
                                    return new ByteArrayInputStream(optionalFile.getBytes(StandardCharsets.UTF_8));
                                }
                            };
                        }
                    });
                } catch (java.net.MalformedURLException e) {
                    throw new AssertionError(e);
                }
            }
        });
        context.scan("com.antmillion.kiwoom");
        return context;
    }

    static void infrastructure(AnnotationConfigApplicationContext context,
            KiwoomTokenServiceTest.MemoryRedis redis, KiwoomTokenServiceTest.LockClient locks, RestTemplate rest) {
        context.getBeanFactory().registerSingleton("redisTemplate", redis);
        context.getBeanFactory().registerSingleton("redissonClient", locks.client());
        // bean 생성 전부터 모든 HTTP를 MockRestServiceServer의 request factory로 묶습니다.
        context.addBeanFactoryPostProcessor(factory -> {
            if (factory.containsBeanDefinition("kiwoomAuthRestTemplate")) {
                ((org.springframework.beans.factory.support.AbstractBeanDefinition)
                        factory.getBeanDefinition("kiwoomAuthRestTemplate")).setInstanceSupplier(() -> rest);
            }
        });
    }
}
