package com.antmillion.kis;

import com.antmillion.config.GlobalExceptionHandler;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.marketdata.dto.MarketIndexCandle;
import com.antmillion.marketdata.dto.MarketIndexChartRequest;
import com.antmillion.marketdata.provider.MarketIndexChartProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 실제 Controller와 MVC 직렬화에 Provider 대역만 연결하며 전체 설정은 로드하지 않습니다. */
public class KisMarketIndexChartControllerTest {
    @Test
    public void existingUrlsJsonOrderDecimalsAndKoreanDateRemainCompatible() throws Exception {
        List<MarketIndexChartRequest> requests = new ArrayList<>();
        MockMvc mvc = mvc(request -> {
            requests.add(request);
            return List.of(new MarketIndexCandle("20261021", "02500.50", "2490.25", "2510.75", "2480.00"),
                    new MarketIndexCandle("20261020", "", null, "0", null));
        });
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            // 기존 경로는 알려지지 않은 지수 코드도 서비스에 그대로 전달합니다.
            for (String code : List.of("0001", "1001", "unknown")) {
                LocalDate before = LocalDate.now(ZoneId.of("Asia/Seoul"));
                MockHttpServletResponse response = mvc.perform(get("/api/kis/marketIndex/" + code))
                        .andReturn().getResponse();
                LocalDate after = LocalDate.now(ZoneId.of("Asia/Seoul"));
                assertEquals(200, response.getStatus());
                MarketIndexChartRequest request = requests.get(requests.size() - 1);
                assertEquals(code, request.getIndexCode());
                assertTrue(!request.getEndDate().isBefore(before) && !request.getEndDate().isAfter(after));
                JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
                assertEquals(2, body.size());
                assertEquals(new ObjectMapper().readTree("{\"stck_bsop_date\":\"20261021\","
                        + "\"bstp_nmix_prpr\":\"02500.50\",\"bstp_nmix_oprc\":\"2490.25\","
                        + "\"bstp_nmix_hgpr\":\"2510.75\",\"bstp_nmix_lwpr\":\"2480.00\"}"), body.get(0));
                assertEquals(new ObjectMapper().readTree("{\"stck_bsop_date\":\"20261020\","
                        + "\"bstp_nmix_prpr\":\"\",\"bstp_nmix_oprc\":null,\"bstp_nmix_hgpr\":\"0\","
                        + "\"bstp_nmix_lwpr\":null}"), body.get(1));
            }
        } finally {
            TimeZone.setDefault(original);
        }
        assertEquals(3, requests.size());
    }

    @Test
    public void emptyListStillSerializesAsEmptyArray() throws Exception {
        MockHttpServletResponse response = mvc(request -> List.of()).perform(
                get("/api/kis/marketIndex/0001")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("[]", response.getContentAsString());
    }

    @Test
    public void nullRowsKeepExistingJsonNullElement() throws Exception {
        MockHttpServletResponse response = mvc(request -> Collections.singletonList(null)).perform(
                get("/api/kis/marketIndex/1001")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("[null]", response.getContentAsString());
    }

    @Test
    public void rateLimitStillUsesExistingAdviceAndRetryAfterResponse() throws Exception {
        MockHttpServletResponse response = mvc(request -> {
            throw new RateLimitExceededException(2_000_000_000L);
        }).perform(get("/api/kis/marketIndex/0001")).andReturn().getResponse();
        assertEquals(429, response.getStatus());
        assertEquals("2", response.getHeader("Retry-After"));
        JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
        assertEquals(3, body.size());
        assertEquals("Rate limit exceeded", body.get("error").asText());
        assertEquals(2, body.get("retryAfter").asInt());
    }

    @Test
    public void providerFailureStillPropagatesWithoutEmptyFallback() {
        RuntimeException failure = new RuntimeException("synthetic failure");
        KisController controller = new KisController(null, null, null, null, request -> { throw failure; }, null);
        assertSame(failure, assertThrows(RuntimeException.class, () -> controller.marketIndexChart("0001")));
    }

    private MockMvc mvc(MarketIndexChartProvider provider) {
        // 다른 기능의 KIS 서비스·외부 WS manager는 생성하지 않습니다.
        return MockMvcBuilders.standaloneSetup(new KisController(null, null, null, null, provider, null))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
