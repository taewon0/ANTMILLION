package com.antmillion.kis;

import com.antmillion.config.GlobalExceptionHandler;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.marketdata.dto.PeriodCandle;
import com.antmillion.marketdata.dto.PeriodChartRequest;
import com.antmillion.marketdata.provider.PeriodChartProvider;
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

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 실제 Controller와 MVC 직렬화에 Provider 대역만 연결하며 전체 설정은 로드하지 않습니다. */
public class KisPeriodChartControllerTest {
    @Test
    public void existingUrlJsonAndAllPeriodDateWindowsRemainCompatible() throws Exception {
        List<PeriodChartRequest> requests = new ArrayList<>();
        MockMvc mvc = mvc(request -> {
            requests.add(request);
            return List.of(new PeriodCandle("20261021", "071000", "70000", "72000", "69000"),
                    new PeriodCandle("20261020", "", null, "0", null));
        });
        for (String period : List.of("D", "W", "M", "Y", "unknown")) {
            LocalDate before = LocalDate.now(ZoneId.of("Asia/Seoul"));
            MockHttpServletResponse response = mvc.perform(get("/api/kis/periodChart/005930")
                    .param("period", period)).andReturn().getResponse();
            LocalDate after = LocalDate.now(ZoneId.of("Asia/Seoul"));
            assertEquals(200, response.getStatus());
            PeriodChartRequest request = requests.get(requests.size() - 1);
            assertEquals("005930", request.getStockCode());
            assertEquals(period, request.getPeriod());
            assertTrue(!request.getEndDate().isBefore(before) && !request.getEndDate().isAfter(after));
            LocalDate end = request.getEndDate();
            LocalDate start;
            switch (period) {
                case "W": start = end.minusWeeks(52); break;
                case "M": start = end.minusMonths(48); break;
                case "Y": start = end.minusYears(30); break;
                default: start = end.minusDays(100);
            }
            assertEquals(start, request.getStartDate());
            JsonNode body = new ObjectMapper().readTree(response.getContentAsByteArray());
            assertEquals(2, body.size());
            assertEquals(new ObjectMapper().readTree("{\"stck_bsop_date\":\"20261021\","
                    + "\"stck_clpr\":\"071000\",\"stck_oprc\":\"70000\","
                    + "\"stck_hgpr\":\"72000\",\"stck_lwpr\":\"69000\"}"), body.get(0));
            assertEquals(new ObjectMapper().readTree("{\"stck_bsop_date\":\"20261020\","
                    + "\"stck_clpr\":\"\",\"stck_oprc\":null,\"stck_hgpr\":\"0\","
                    + "\"stck_lwpr\":null}"), body.get(1));
        }
    }

    @Test
    public void emptyListStillSerializesAsEmptyArray() throws Exception {
        MockHttpServletResponse response = mvc(request -> List.of()).perform(
                get("/api/kis/periodChart/005930").param("period", "D")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("[]", response.getContentAsString());
    }

    @Test
    public void nullRowsKeepExistingJsonNullElement() throws Exception {
        MockHttpServletResponse response = mvc(request -> Collections.singletonList(null)).perform(
                get("/api/kis/periodChart/005930").param("period", "D")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("[null]", response.getContentAsString());
    }

    @Test
    public void missingPeriodStillReturnsBadRequestWithoutProviderCall() throws Exception {
        MockHttpServletResponse response = mvc(request -> {
            throw new AssertionError("필수 기간 값이 없으면 Provider를 호출하면 안 됩니다.");
        }).perform(get("/api/kis/periodChart/005930")).andReturn().getResponse();
        assertEquals(400, response.getStatus());
    }

    @Test
    public void rateLimitStillUsesExistingAdviceAndRetryAfterResponse() throws Exception {
        MockHttpServletResponse response = mvc(request -> {
            throw new RateLimitExceededException(2_000_000_000L);
        }).perform(get("/api/kis/periodChart/005930").param("period", "D"))
                .andReturn().getResponse();
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
        KisController controller = new KisController(null, null, request -> { throw failure; });
        assertSame(failure, assertThrows(RuntimeException.class, () -> controller.periodChart("005930", "D")));
    }

    private MockMvc mvc(PeriodChartProvider provider) {
        // 다른 기능의 KIS 서비스·외부 WS manager는 생성하지 않습니다.
        return MockMvcBuilders.standaloneSetup(new KisController(null, null, provider))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
