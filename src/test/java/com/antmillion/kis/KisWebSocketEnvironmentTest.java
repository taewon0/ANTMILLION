package com.antmillion.kis;

import com.antmillion.kis.config.KisWebSocketConfig;
import com.antmillion.kis.constant.KisApiConstant;
import com.antmillion.kis.dto.KisWebSocketConnectionScope;
import com.antmillion.kis.handler.KisWebSocketHandler;
import com.antmillion.kis.manager.KisWebSocketManager;
import com.antmillion.kis.service.KisApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.AbstractWebSocketClient;
import org.springframework.web.socket.client.WebSocketClient;

import java.io.IOException;
import java.net.URI;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.antmillion.kis.KisWebSocketRecoveryTest.Manager.response;
import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 발급·연결 코드에 HTTP/WS/시각/타이머 대역만 사용하며 설정 파일·DB·Redis는 로드하지 않습니다. */
public class KisWebSocketEnvironmentTest {
    private static class Manager extends KisWebSocketManager {
        final KisWebSocketRecoveryTest.Timer timer = new KisWebSocketRecoveryTest.Timer();
        final List<String> urls = new ArrayList<>();
        final List<KisWebSocketRecoveryTest.Connection> connections = new ArrayList<>();
        LocalTime market = LocalTime.of(10, 0);
        long now;
        boolean failHandshake;

        Manager(KisApiService service) { super(service, null); }
        @Override protected ThreadPoolTaskScheduler createScheduler() { return timer; }
        @Override protected LocalTime marketTime() { return market; }
        @Override protected long nowMillis() { return now; }
        @Override protected WebSocketClient createWebSocketClient() {
            return new AbstractWebSocketClient() {
                @Override protected ListenableFuture<WebSocketSession> doHandshakeInternal(
                        WebSocketHandler handler, HttpHeaders headers, URI uri, List<String> protocols,
                        List<WebSocketExtension> extensions, Map<String, Object> attributes) {
                    urls.add(uri.toString());
                    SettableListenableFuture<WebSocketSession> future = new SettableListenableFuture<>();
                    if (failHandshake) {
                        future.setException(new IOException("synthetic handshake failure"));
                        return future;
                    }
                    KisWebSocketRecoveryTest.Connection connection = new KisWebSocketRecoveryTest.Connection();
                    connections.add(connection);
                    connection.onSend = payload -> {
                        try {
                            JsonNode request = new ObjectMapper().readTree(payload);
                            JsonNode input = request.path("body").path("input");
                            response((KisWebSocketHandler) handler, connection, input.path("tr_key").asText(),
                                    input.path("tr_id").asText(), "0",
                                    "2".equals(request.path("header").path("tr_type").asText())
                                            ? "UNSUBSCRIBE SUCCESS" : "SUBSCRIBE SUCCESS");
                        } catch (IOException e) { throw new AssertionError(e); }
                    };
                    try { handler.afterConnectionEstablished(connection.session); }
                    catch (Exception e) { throw new AssertionError(e); }
                    future.set(connection.session);
                    return future;
                }
            };
        }
        void tick() { timer.task.run(); }
        void advance(long millis) { now += millis; tick(); }
        KisWebSocketRecoveryTest.Connection current() { return connections.get(connections.size() - 1); }
    }

    private static class Fixture implements AutoCloseable {
        final KisWebSocketConfig config = new KisWebSocketConfig();
        final RestTemplate rest = new RestTemplate();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        final KisApiService service = new KisApiService(null, config, rest, null, null, null, null, null, null, null);
        final Manager manager = new Manager(service);

        Fixture(boolean real) { configure(real); }
        void configure(boolean real) {
            config.mode = real ? "real" : "virtual";
            config.appKey = real ? "synthetic-real-key" : "synthetic-virtual-key";
            config.secretKey = real ? "synthetic-real-secret" : "synthetic-virtual-secret";
        }
        void expectApproval(boolean real) { expectApproval(real, () -> { }); }
        void expectApproval(boolean real, Runnable duringResponse) {
            server.expect(requestTo(base(real) + KisApiConstant.WEB_SOCKET_PATH))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(request -> {
                        ObjectMapper mapper = new ObjectMapper();
                        assertEquals(mapper.readTree("{\"grant_type\":\"client_credentials\",\"appkey\":\"synthetic-"
                                + (real ? "real" : "virtual") + "-key\",\"secretkey\":\"synthetic-"
                                + (real ? "real" : "virtual") + "-secret\"}"),
                                mapper.readTree(((MockClientHttpRequest) request).getBodyAsString()));
                    })
                    .andRespond(request -> {
                        duringResponse.run();
                        return withSuccess("{\"approval_key\":\"" + approval(real) + "\"}", MediaType.APPLICATION_JSON)
                                .createResponse(request);
                    });
        }
        @Override public void close() { manager.stop(); server.verify(); }
    }

    @Test
    public void bothEnvironmentsMatchApprovalHandshakeAndReconnectOnlyRemainingReferences() throws Exception {
        for (boolean real : new boolean[]{true, false}) {
            try (Fixture fixture = new Fixture(real)) {
                fixture.expectApproval(real);
                fixture.expectApproval(real);
                Manager manager = fixture.manager;
                manager.start();
                manager.subscribeCurrentMarket("005930", true);
                manager.subscribeCurrentMarket("000660", false);
                manager.tick();
                assertEquals(List.of(ws(real)), manager.urls);
                assertApprovalAndTr(manager.current(), approval(real), "H0STCNT0", "H0STASP0");
                KisWebSocketRecoveryTest.Connection old = manager.current();
                manager.connectionLost(old.session);
                manager.unsubscribeCurrentMarket("000660", false);
                manager.advance(999);
                assertEquals(1, manager.urls.size());
                manager.advance(1);
                assertFalse(old.open.get());
                assertEquals(List.of(ws(real), ws(real)), manager.urls);
                assertApprovalAndTr(manager.current(), approval(real), "H0STCNT0");
                assertFalse(manager.isCurrentSession(old.session));
                manager.unsubscribeCurrentMarket("005930", true);
                manager.tick();
                assertFalse(manager.current().open.get());
                manager.advance(30000);
                assertEquals(2, manager.urls.size());
            }
        }
    }

    @Test
    public void configurationChangesDuringApprovalCannotMixCurrentAttemptInEitherDirection() throws Exception {
        for (boolean real : new boolean[]{true, false}) {
            try (Fixture fixture = new Fixture(real)) {
                fixture.expectApproval(real, () -> fixture.configure(!real));
                fixture.expectApproval(!real);
                Manager manager = fixture.manager;
                manager.start();
                manager.subscribeCurrentMarket("005930", true);
                manager.tick();
                assertEquals(List.of(ws(real)), manager.urls);
                assertApprovalAndTr(manager.current(), approval(real), "H0STCNT0");
                manager.connectionLost(manager.current().session);
                manager.advance(1000);
                assertEquals(List.of(ws(real), ws(!real)), manager.urls);
                assertApprovalAndTr(manager.current(), approval(!real), "H0STCNT0");
            }
        }
    }

    @Test
    public void bothEnvironmentsKeepExistingMarketTransitionAt1530AndMidnight() throws Exception {
        for (boolean real : new boolean[]{true, false}) {
            try (Fixture fixture = new Fixture(real)) {
                fixture.expectApproval(real);
                fixture.expectApproval(real);
                fixture.expectApproval(real);
                Manager manager = fixture.manager;
                manager.start();
                manager.market = LocalTime.of(15, 29, 59);
                manager.subscribeCurrentMarket("005930", true);
                manager.subscribeCurrentMarket("005930", false);
                manager.tick();
                assertApprovalAndTr(manager.current(), approval(real), "H0STCNT0", "H0STASP0");
                KisWebSocketRecoveryTest.Connection old = manager.current();
                manager.market = LocalTime.of(15, 30);
                manager.tick();
                assertFalse(old.open.get());
                assertApprovalAndTr(manager.current(), approval(real), "H0UNCNT0", "H0UNASP0");
                old = manager.current();
                manager.market = LocalTime.MIDNIGHT;
                manager.tick();
                assertFalse(old.open.get());
                assertApprovalAndTr(manager.current(), approval(real), "H0STCNT0", "H0STASP0");
                assertEquals(List.of(ws(real), ws(real), ws(real)), manager.urls);
            }
        }
    }

    @Test
    public void approvalFailureSkipsHandshakeAndRetriesInSameEnvironment() {
        for (boolean real : new boolean[]{true, false}) {
            try (Fixture fixture = new Fixture(real)) {
                fixture.server.expect(requestTo(base(real) + KisApiConstant.WEB_SOCKET_PATH))
                        .andRespond(withServerError());
                fixture.expectApproval(real);
                Manager manager = fixture.manager;
                manager.start();
                manager.subscribeCurrentMarket("005930", false);
                manager.tick();
                assertTrue(manager.urls.isEmpty());
                manager.advance(999);
                assertTrue(manager.urls.isEmpty());
                manager.advance(1);
                assertEquals(List.of(ws(real)), manager.urls);
                assertEquals(1, manager.current().messages.size());
            }
        }
    }

    @Test
    public void handshakeFailureKeepsEnvironmentAndRestoresReferencesAfterBackoff() {
        for (boolean real : new boolean[]{true, false}) {
            try (Fixture fixture = new Fixture(real)) {
                fixture.expectApproval(real);
                fixture.expectApproval(real);
                Manager manager = fixture.manager;
                manager.failHandshake = true;
                manager.start();
                manager.subscribeCurrentMarket("005930", false);
                manager.tick();
                assertTrue(manager.connections.isEmpty());
                manager.failHandshake = false;
                manager.advance(1000);
                assertEquals(List.of(ws(real), ws(real)), manager.urls);
                assertEquals(1, manager.current().messages.size());
            }
        }
    }

    @Test
    public void legacyApprovalEntryPointUsesModeAndAcceptsShortKeyWithoutLoggingItsPrefix() {
        for (boolean real : new boolean[]{true, false}) {
            try (Fixture fixture = new Fixture(real)) {
                fixture.server.expect(requestTo(base(real) + KisApiConstant.WEB_SOCKET_PATH))
                        .andRespond(withSuccess("{\"approval_key\":\"key\"}", MediaType.APPLICATION_JSON));
                assertEquals("key", fixture.service.getKisApprovalKey());
                assertTrue(fixture.manager.urls.isEmpty());
            }
        }
    }

    @Test
    public void scopeKeepsLegacyModeResolutionAndDoesNotExposeCredentialsInToString() {
        KisWebSocketConfig config = new KisWebSocketConfig();
        config.appKey = "synthetic-key";
        config.secretKey = "synthetic-secret";
        for (String mode : new String[]{"real", "REAL", "virtual", "unknown", "", null}) {
            config.mode = mode;
            KisWebSocketConnectionScope scope = config.connectionScope();
            boolean real = "real".equalsIgnoreCase(mode);
            assertEquals(config.getBaseUrl(), scope.getBaseUrl());
            assertEquals(ws(real), scope.getWebSocketUrl());
            assertFalse(scope.toString().contains(config.appKey));
            assertFalse(scope.toString().contains(config.secretKey));
        }
    }

    private static void assertApprovalAndTr(KisWebSocketRecoveryTest.Connection connection,
            String key, String... expectedTrIds) throws Exception {
        List<String> actual = new ArrayList<>();
        for (String payload : connection.messages) {
            JsonNode request = new ObjectMapper().readTree(payload);
            assertEquals(key, request.path("header").path("approval_key").asText());
            actual.add(request.path("body").path("input").path("tr_id").asText());
        }
        assertEquals(List.of(expectedTrIds), actual);
    }

    private static String base(boolean real) {
        return real ? KisApiConstant.REAL_BASE_URL : KisApiConstant.VIRTUAL_BASE_URL;
    }
    private static String ws(boolean real) {
        return real ? "ws://ops.koreainvestment.com:21000" : "ws://ops.koreainvestment.com:31000";
    }
    private static String approval(boolean real) { return real ? "synthetic-real-approval" : "synthetic-virtual-approval"; }
}
