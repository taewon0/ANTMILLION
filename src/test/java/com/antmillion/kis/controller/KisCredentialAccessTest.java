package com.antmillion.kis.controller;

import com.antmillion.auth.config.SecurityConfig;
import com.antmillion.auth.jwt.JwtProvider;
import com.antmillion.auth.service.SignService;
import com.antmillion.auth.token.RefreshTokenStore;
import com.antmillion.kis.config.KisConfig;
import com.antmillion.kis.config.KisWebSocketConfig;
import com.antmillion.kis.constant.KisApiConstant;
import com.antmillion.kis.dto.MarketIndexPrice;
import com.antmillion.kis.repository.KisAccessTokenRedisRepository;
import com.antmillion.kis.repository.KisMarketIndexChartRedisRepository;
import com.antmillion.kis.service.KisApiService;
import com.antmillion.kis.service.RateLimitedKisApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import javax.servlet.http.Cookie;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

public class KisCredentialAccessTest {
    private static final String BASE_URL = "https://kis.invalid";
    private static final String ACCESS_TOKEN = "synthetic-access-token";
    private static final String APPROVAL_KEY = "synthetic-approval-key";
    private static final String[] CREDENTIAL_PATHS = {
            "/api/kis/auth", "/api/kis/approval"
    };

    private AnnotationConfigWebApplicationContext security;
    private MockRestServiceServer kisServer;
    private RecordingKisApiService service;
    private MemoryIndexRepository indexRepository;
    private MockMvc mvc;
    private String accessJwt;

    @Before
    public void setUp() {
        SecurityContextHolder.clearContext();
        // No root context, properties, component scan, DB, Redis or scheduler.
        security = new AnnotationConfigWebApplicationContext();
        security.setServletContext(new MockServletContext());
        security.register(SecurityTestConfig.class);
        security.refresh();
        accessJwt = security.getBean(JwtProvider.class)
                .createAccessToken(1L, 1, "LOCAL", "kis-access-test");

        RestTemplate http = new RestTemplate();
        // Replaces the request factory: even unexpected requests cannot use the network.
        kisServer = MockRestServiceServer.bindTo(http).build();
        KisConfig config = new KisConfig() {
            @Override public String getBaseUrl() { return BASE_URL; }
        };
        config.appKey = "synthetic-app-key";
        config.appSecret = "synthetic-app-secret";
        KisWebSocketConfig wsConfig = new KisWebSocketConfig() {
            @Override public String getBaseUrl() { return BASE_URL; }
        };
        wsConfig.appKey = config.appKey;
        wsConfig.secretKey = config.appSecret;
        indexRepository = new MemoryIndexRepository();
        service = new RecordingKisApiService(config, wsConfig, http, indexRepository);
        mvc = MockMvcBuilders.standaloneSetup(new KisController(service, null))
                .addFilters(security.getBean(FilterChainProxy.class)).build();
    }

    @After
    public void tearDown() {
        SecurityContextHolder.clearContext();
        if (security != null) security.close();
    }

    @Test
    public void anonymousRequestsCannotReachCredentialService() throws Exception {
        assertCredentialPathsDenied(null, 401);
    }

    @Test
    public void authenticatedRequestsCannotReachCredentialService() throws Exception {
        assertCredentialPathsDenied(accessJwt, 403);
    }

    @Test
    public void invalidJwtCannotReachCredentialService() throws Exception {
        assertCredentialPathsDenied("invalid-jwt", 401);
    }

    @Test
    public void otherHttpMethodsCannotReachCredentialService() throws Exception {
        for (String path : CREDENTIAL_PATHS) {
            for (HttpMethod method : new HttpMethod[]{HttpMethod.POST, HttpMethod.PUT,
                    HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.HEAD, HttpMethod.OPTIONS}) {
                assertEquals(401, mvc.perform(api(method, path)).andReturn().getResponse().getStatus());
                assertEquals(403, mvc.perform(api(method, path).cookie(new Cookie("AT", accessJwt)))
                        .andReturn().getResponse().getStatus());
            }
        }
        assertNoCredentialCalls();
    }

    @Test
    public void credentialHandlersAreAbsentEvenWithoutSecurityFilter() throws Exception {
        MockMvc withoutFilters = MockMvcBuilders.standaloneSetup(new KisController(service, null)).build();
        for (String path : CREDENTIAL_PATHS) {
            for (String suffix : new String[]{"", "/", ".json"}) {
                assertEquals(404, withoutFilters.perform(api(HttpMethod.GET, path + suffix))
                        .andReturn().getResponse().getStatus());
            }
        }
        assertNoCredentialCalls();
    }

    @Test
    public void publicMarketIndexStillUsesInternalTokenAndPreservesResponse() throws Exception {
        assertMarketIndexResponse(null);
    }

    @Test
    public void authenticatedMarketIndexStillUsesInternalTokenAndPreservesResponse() throws Exception {
        assertMarketIndexResponse(accessJwt);
    }

    @Test
    public void internalApprovalIssuanceRemainsAvailable() {
        kisServer.expect(requestTo(BASE_URL + KisApiConstant.WEB_SOCKET_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(req -> {
                    JsonNode body = new ObjectMapper().readTree(((MockClientHttpRequest) req).getBodyAsBytes());
                    assertEquals("client_credentials", body.get("grant_type").asText());
                    assertEquals("synthetic-app-key", body.get("appkey").asText());
                    assertEquals("synthetic-app-secret", body.get("secretkey").asText());
                })
                .andRespond(withSuccess("{\"approval_key\":\"" + APPROVAL_KEY + "\"}", MediaType.APPLICATION_JSON));
        assertEquals(APPROVAL_KEY, service.getKisApprovalKey());
        assertEquals(1, service.approvalCalls);
        assertEquals(0, service.tokenCalls);
        kisServer.verify();
    }

    private void assertCredentialPathsDenied(String jwt, int status) throws Exception {
        for (String path : CREDENTIAL_PATHS) {
            for (String suffix : new String[]{"", "/", "/extra"}) {
                MockHttpServletRequestBuilder req = api(HttpMethod.GET, path + suffix).param("probe", "1");
                if (jwt != null) req.cookie(new Cookie("AT", jwt));
                MvcResult result = mvc.perform(req).andReturn();
                assertEquals(path + suffix, status, result.getResponse().getStatus());
                assertEquals("", result.getResponse().getContentAsString());
                assertNull(result.getResponse().getRedirectedUrl());
            }
        }
        assertNoCredentialCalls();
    }

    private void assertNoCredentialCalls() {
        assertEquals(0, service.tokenCalls);
        assertEquals(0, service.approvalCalls);
        kisServer.verify();
    }

    private void assertMarketIndexResponse(String jwt) throws Exception {
        kisServer.expect(req -> {
            assertEquals(BASE_URL + KisApiConstant.MARKET_INDEX_PATH, req.getURI().toString().split("\\?")[0]);
            assertTrue(req.getURI().getQuery().contains("FID_INPUT_ISCD=0001"));
        }).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andRespond(withSuccess("{\"output2\":[{\"stck_bsop_date\":\"20261016\","
                        + "\"bstp_nmix_prpr\":\"2500.00\"}]}", MediaType.APPLICATION_JSON));
        MockHttpServletRequestBuilder req = api(HttpMethod.GET, "/api/kis/marketIndex/0001");
        if (jwt != null) req.cookie(new Cookie("AT", jwt));
        MvcResult result = mvc.perform(req).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsByteArray());
        assertTrue(body.isArray());
        assertEquals(1, body.size());
        assertEquals("20261016", body.get(0).get("stck_bsop_date").asText());
        assertEquals("2500.00", body.get(0).get("bstp_nmix_prpr").asText());
        assertFalse(result.getResponse().getContentAsString().contains(ACCESS_TOKEN));
        assertEquals("0001", indexRepository.savedIndexCode);
        assertEquals(1, indexRepository.saved.size());
        assertEquals(1, service.tokenCalls);
        assertEquals(0, service.approvalCalls);
        kisServer.verify();
    }

    private MockHttpServletRequestBuilder api(HttpMethod method, String path) {
        return request(method, "/antmillion" + path).contextPath("/antmillion")
                .with(req -> {
                    // MockMvc's servletPath builder rejects trailing slashes; the servlet API allows them.
                    req.setServletPath(path);
                    return req;
                });
    }

    @Configuration
    @Import(SecurityConfig.class)
    public static class SecurityTestConfig {
        @Bean public JwtProvider jwtProvider() {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            return new JwtProvider(Base64.getEncoder().encodeToString(key), 900, 1800);
        }

        @Bean public RefreshTokenStore refreshTokenStore() {
            return new RefreshTokenStore(null) {
                @Override public String get(long userId) { throw new AssertionError("Unexpected Redis read"); }
                @Override public void save(long userId, String token, long ttl) { throw new AssertionError("Unexpected Redis write"); }
                @Override public void delete(long userId) { throw new AssertionError("Unexpected Redis delete"); }
            };
        }

        @Bean public SignService signService(JwtProvider jwt, RefreshTokenStore store) {
            // API requests skip auto-refresh; no mapper or external client is created.
            return new SignService(null, null, null, null, jwt, store, null);
        }
    }

    private static class RecordingKisApiService extends KisApiService {
        private int tokenCalls;
        private int approvalCalls;

        RecordingKisApiService(KisConfig config, KisWebSocketConfig wsConfig, RestTemplate http,
                              MemoryIndexRepository indexRepository) {
            super(config, wsConfig, http, new KisAccessTokenRedisRepository(null) {
                @Override public Optional<String> findAccessToken() { return Optional.of(ACCESS_TOKEN); }
            }, null, indexRepository, null, null, null, new RateLimitedKisApiClient(null));
        }

        @Override public String getKisAccessToken() {
            tokenCalls++;
            return super.getKisAccessToken();
        }

        @Override public String getKisApprovalKey() {
            approvalCalls++;
            return super.getKisApprovalKey();
        }
    }

    private static class MemoryIndexRepository extends KisMarketIndexChartRedisRepository {
        private String savedIndexCode;
        private List<MarketIndexPrice> saved;

        MemoryIndexRepository() { super(null, null); }
        @Override public Optional<List<MarketIndexPrice>> getChartData(String indexCode) { return Optional.empty(); }
        @Override public void save(String indexCode, List<MarketIndexPrice> prices) {
            savedIndexCode = indexCode;
            saved = prices;
        }
    }
}
