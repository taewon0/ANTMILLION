package com.antmillion.kis;

import com.antmillion.config.GlobalExceptionHandler;
import com.antmillion.kis.controller.KisController;
import com.antmillion.kis.exception.RateLimitExceededException;
import com.antmillion.marketdata.dto.InvestorFlow;
import com.antmillion.marketdata.provider.InvestorFlowProvider;
import com.antmillion.marketdata.service.InvestorSignalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 공통 신호등 서비스·실제 Controller·기존 오류 advice만 사용하는 독립 MVC 검증입니다. */
public class KisInvestorSignalControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void bothPositiveAndNegativeKeepColorsAndSingleJsonField() throws Exception {
        assertColor("+0012", "003", "GREEN");
        assertColor("-0012", "-003", "RED");
    }

    @Test
    public void mixedAndZeroQuantitiesKeepYellow() throws Exception {
        assertColor("1", "-1", "YELLOW");
        assertColor("-1", "1", "YELLOW");
        assertColor("0", "1", "YELLOW");
        assertColor("-1", "0", "YELLOW");
        assertColor("0", "0", "YELLOW");
    }

    @Test
    public void missingQuantitiesKeepExplicitNullColor() throws Exception {
        assertColor(null, null, null);
        assertColor(null, "1", null);
        assertColor("1", null, null);
        // 기존처럼 누락 검사를 숫자 파싱보다 먼저 수행합니다.
        assertColor("invalid", null, null);
    }

    @Test
    public void longBoundariesAreAcceptedWithoutUsingCombinedQuantity() throws Exception {
        assertColor(Long.toString(Long.MAX_VALUE), "1", "GREEN");
        assertColor(Long.toString(Long.MIN_VALUE), "-1", "RED");
    }

    @Test
    public void malformedEmptyWhitespaceAndOverflowKeepNumberFormatFailure() {
        for (String value : new String[]{"invalid", "", " 1", "9223372036854775808"}) {
            assertThrows(NumberFormatException.class, () -> controller(code -> new InvestorFlow(value, "1", null))
                    .getForeignerOrganization("000001"));
            assertThrows(NumberFormatException.class, () -> controller(code -> new InvestorFlow("1", value, null))
                    .getForeignerOrganization("000001"));
        }
    }

    @Test
    public void providerErrorsPropagateWithoutNeutralFallback() {
        RuntimeException failure = new RuntimeException("synthetic failure");
        assertSame(failure, assertThrows(RuntimeException.class, () -> controller(code -> { throw failure; })
                .getForeignerOrganization("000001")));
    }

    @Test
    public void rateLimitKeeps429BodyAndRetryHeader() throws Exception {
        MockHttpServletResponse response = mvc(code -> { throw new RateLimitExceededException(2_000_000_000L); })
                .perform(get("/api/kis/foreigner-organization/000001")).andReturn().getResponse();
        assertEquals(429, response.getStatus());
        assertEquals("2", response.getHeader("Retry-After"));
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        assertEquals(3, body.size());
        assertEquals("Rate limit exceeded", body.get("error").asText());
        assertEquals(2, body.get("retryAfter").asInt());
    }

    private void assertColor(String foreign, String organization, String expected) throws Exception {
        MockHttpServletResponse response = mvc(code -> {
            assertEquals("000001", code);
            return new InvestorFlow(foreign, organization, "unused");
        }).perform(get("/api/kis/foreigner-organization/000001")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        assertEquals(1, body.size());
        assertTrue(body.has("signalColor"));
        if (expected == null) assertTrue(body.get("signalColor").isNull());
        else assertEquals(expected, body.get("signalColor").asText());
    }

    private KisController controller(InvestorFlowProvider provider) {
        return new KisController(new InvestorSignalService(provider), null, null, null, null, null);
    }

    private MockMvc mvc(InvestorFlowProvider provider) {
        return MockMvcBuilders.standaloneSetup(controller(provider))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
