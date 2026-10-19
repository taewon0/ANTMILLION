package com.antmillion.kis;

import com.antmillion.kis.controller.MockDataController;
import com.antmillion.kis.service.MockDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Only mock beans and MVC are loaded: no properties, DB, Redis, KIS or application schedulers. */
public class MockDevelopmentModeTest {
    @Configuration
    @ComponentScan(basePackageClasses = MockDataService.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = MockDataService.class))
    static class RootConfig {
        @Bean
        public BlockingQueue<Message<?>> messages() { return new LinkedBlockingQueue<>(); }

        @Bean
        public SimpMessagingTemplate messagingTemplate(BlockingQueue<Message<?>> messages) {
            ExecutorSubscribableChannel channel = new ExecutorSubscribableChannel();
            channel.subscribe(messages::add);
            return new SimpMessagingTemplate(channel);
        }
    }

    @Configuration
    @EnableWebMvc
    @ComponentScan(basePackageClasses = MockDataController.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = MockDataController.class))
    static class ServletConfig { }

    static class Application implements AutoCloseable {
        final AnnotationConfigWebApplicationContext root = new AnnotationConfigWebApplicationContext();
        final AnnotationConfigWebApplicationContext servlet = new AnnotationConfigWebApplicationContext();
        final MockMvc mvc;

        Application(String... profiles) {
            MockServletContext container = new MockServletContext();
            root.setServletContext(container);
            root.getEnvironment().setActiveProfiles(profiles);
            root.register(RootConfig.class);
            root.refresh();
            servlet.setParent(root);
            servlet.setServletContext(container);
            servlet.register(ServletConfig.class);
            servlet.refresh();
            mvc = webAppContextSetup(servlet).build();
        }

        boolean pageFlag() {
            StandardEvaluationContext context = new StandardEvaluationContext();
            context.setBeanResolver(new BeanFactoryResolver(servlet));
            return Boolean.TRUE.equals(new SpelExpressionParser()
                    .parseExpression("@environment.acceptsProfiles('dev-mock')").getValue(context));
        }

        @Override
        public void close() {
            servlet.close();
            root.close();
        }
    }

    @Test
    public void defaultHasNoMockBeansOrRoutesEvenWithBrowserFlags() throws Exception {
        assertDisabled();
    }

    @Test
    public void genericDevelopmentProfileDoesNotEnableMock() throws Exception {
        assertDisabled("dev");
    }

    private void assertDisabled(String... profiles) throws Exception {
        try (Application app = new Application(profiles)) {
            assertTrue(app.root.getBeansOfType(MockDataService.class).isEmpty());
            assertTrue(app.servlet.getBeansOfType(MockDataController.class).isEmpty());
            assertFalse(app.pageFlag());
            for (String path : new String[]{"/add-stocks", "/start", "/stop", "/clear", "/status"}) {
                MockHttpServletRequestBuilder request = "/status".equals(path)
                        ? get("/api/kis/mock" + path) : post("/api/kis/mock" + path);
                app.mvc.perform(request.param("kisMockEnabled", "true").contentType("application/json")
                        .content("[{\"stockCode\":\"TEST01\",\"basePrice\":10000}]"))
                        .andExpect(status().isNotFound());
            }
        }
    }

    @Test
    public void explicitProfilePreservesControlAndBroadcastContract() throws Exception {
        try (Application app = new Application("dev-mock")) {
            assertTrue(app.pageFlag());
            app.mvc.perform(post("/api/kis/mock/add-stocks").contentType("application/json")
                    .content("[{\"stockCode\":\"TEST01\",\"basePrice\":10000}]"))
                    .andExpect(status().isOk());
            app.mvc.perform(post("/api/kis/mock/start")).andExpect(status().isOk());
            BlockingQueue<?> messages = app.root.getBean(BlockingQueue.class);
            Message<?> message = (Message<?>) messages.poll(3, TimeUnit.SECONDS);
            assertNotNull("Mock must broadcast without a KIS connection", message);
            assertEquals("/topic/kis-trade/presentTEST01", SimpMessageHeaderAccessor.getDestination(message.getHeaders()));
            Map<?, ?> payload = (Map<?, ?>) message.getPayload();
            assertEquals("TEST01", payload.get("mkscShrnIscd"));
            assertTrue(Integer.parseInt((String) payload.get("stckPrpr")) > 0);
            for (String field : new String[]{"prdySign", "prdyVrss", "prdyCtrt", "shnuRate"}) {
                assertTrue(payload.containsKey(field));
            }
            app.mvc.perform(post("/api/kis/mock/stop")).andExpect(status().isOk());
            app.mvc.perform(post("/api/kis/mock/clear")).andExpect(status().isOk());
            String json = app.mvc.perform(get("/api/kis/mock/status")).andReturn().getResponse().getContentAsString();
            assertFalse(new ObjectMapper().readTree(json).get("isRunning").asBoolean());
            assertEquals(0, new ObjectMapper().readTree(json).get("stockCount").asInt());
        }
    }

    @Test
    public void contextCloseStopsActiveMockBroadcast() throws Exception {
        Application app = new Application("dev-mock");
        MockDataService service = app.root.getBean(MockDataService.class);
        try {
            service.addMockStock("TEST01", 10000);
            service.startMockData();
            assertTrue(service.isRunning());
        } finally {
            app.close();
        }
        assertFalse(service.isRunning());
    }
}
