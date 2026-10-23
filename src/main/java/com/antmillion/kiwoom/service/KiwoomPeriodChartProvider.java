package com.antmillion.kiwoom.service;

import com.antmillion.kiwoom.dto.KiwoomPeriodChartRequest;
import com.antmillion.kiwoom.dto.KiwoomPeriodChartResponse;
import com.antmillion.kiwoom.dto.KiwoomPeriodChartResponse.Price;
import com.antmillion.marketdata.dto.PeriodCandle;
import com.antmillion.marketdata.dto.PeriodChartRequest;
import com.antmillion.marketdata.provider.PeriodChartProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 내부 명시 호출 전용이며 Spring bean·운영 Provider로 등록하지 않습니다. */
public final class KiwoomPeriodChartProvider implements PeriodChartProvider {
    private static final int MAX_PAGES = 100;
    private final KiwoomTokenService tokens;
    private final RestTemplate rest;
    private final KiwoomRestRateLimiter limiter;

    public KiwoomPeriodChartProvider(KiwoomTokenService tokens, RestTemplate rest, KiwoomRestRateLimiter limiter) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.rest = Objects.requireNonNull(rest, "rest");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    @Override
    public List<PeriodCandle> getPeriodChart(PeriodChartRequest request) {
        validate(request);
        String apiId = apiId(request.getPeriod());
        KiwoomPeriodChartRequest body = new KiwoomPeriodChartRequest(request.getStockCode(),
                request.getEndDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        List<PeriodCandle> result = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        String nextKey = null;
        LocalDate previousDate = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            // 각 페이지도 현재가와 동일한 실전 예산/모의 TR별 예산을 소비합니다.
            // 한도 소진·후속 페이지 오류 시 누적 결과를 반환하지 않습니다.
            limiter.acquirePeriodChart(tokens, apiId);
            ResponseEntity<KiwoomPeriodChartResponse> response = fetch(apiId, body, nextKey);
            KiwoomPeriodChartResponse data = response.getBody();
            if (data == null || (data.getReturnCode() != null && data.getReturnCode() != 0)
                    || (data.getStockCode() != null && !data.getStockCode().isBlank()
                    && !request.getStockCode().equals(data.getStockCode()))) {
                throw invalidResponse();
            }
            List<Price> prices = prices(data, request.getPeriod());
            if (prices == null) throw invalidResponse();
            boolean reachedStart = false;
            LocalDate pageOldest = null;
            for (Price price : prices) {
                if (price == null) {
                    result.add(null);
                    continue;
                }
                LocalDate date = date(price.getDate());
                // 기준일 이전의 최신순 응답만 허용하여 조기 종료로 인한 누락을 막습니다.
                if (date.isAfter(request.getEndDate()) || (previousDate != null && date.isAfter(previousDate))) {
                    throw invalidResponse();
                }
                previousDate = date;
                pageOldest = date;
                if (!date.isBefore(request.getStartDate())) {
                    result.add(new PeriodCandle(price.getDate(), price(price.getClosePrice()),
                            price(price.getOpenPrice()), price(price.getHighPrice()), price(price.getLowPrice())));
                }
                if (!date.isAfter(request.getStartDate())) reachedStart = true;
            }
            String continuation = response.getHeaders().getFirst("cont-yn");
            if (continuation != null && !continuation.isEmpty() && !"N".equals(continuation)
                    && !"Y".equals(continuation)) throw invalidResponse();
            if (reachedStart || !"Y".equals(continuation)) return result;
            // 빈 페이지·반복 키·상한 초과를 정상적인 부분 결과로 처리하지 않습니다.
            String returnedKey = response.getHeaders().getFirst("next-key");
            if (pageOldest == null || returnedKey == null || returnedKey.isBlank()
                    || returnedKey.length() > 50 || !seenKeys.add(returnedKey)) throw invalidResponse();
            nextKey = returnedKey;
        }
        throw new IllegalStateException("Kiwoom 기간별 차트 연속 조회 상한을 초과했습니다.");
    }

    private ResponseEntity<KiwoomPeriodChartResponse> fetch(String apiId, KiwoomPeriodChartRequest body,
                                                          String nextKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        try {
            headers.setBearerAuth(tokens.getAccessToken());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Kiwoom 기간별 차트 토큰 조회에 실패했습니다.");
        }
        headers.set("api-id", apiId);
        if (nextKey != null) {
            headers.set("cont-yn", "Y");
            headers.set("next-key", nextKey);
        }
        try {
            return rest.exchange(tokens.getBaseUrl() + "/api/dostk/chart", HttpMethod.POST,
                    new HttpEntity<>(body, headers), KiwoomPeriodChartResponse.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("Kiwoom 기간별 차트 HTTP 요청에 실패했습니다.");
        }
    }

    private static void validate(PeriodChartRequest request) {
        if (request == null || request.getStockCode() == null || !request.getStockCode().matches("[0-9A-Z]{6}")
                || request.getStartDate() == null || request.getEndDate() == null
                || request.getStartDate().isAfter(request.getEndDate())
                || request.getStartDate().getYear() < 1 || request.getEndDate().getYear() > 9999) {
            throw new IllegalArgumentException("Kiwoom 기간별 차트의 KRX 종목·조회 기간이 유효하지 않습니다.");
        }
    }

    private static String apiId(String period) {
        if ("D".equals(period)) return "ka10081";
        if ("W".equals(period)) return "ka10082";
        if ("M".equals(period)) return "ka10083";
        if ("Y".equals(period)) return "ka10094";
        throw new IllegalArgumentException("Kiwoom 기간별 차트는 D/W/M/Y만 지원합니다.");
    }

    private static List<Price> prices(KiwoomPeriodChartResponse data, String period) {
        if ("D".equals(period)) return data.getDaily();
        if ("W".equals(period)) return data.getWeekly();
        if ("M".equals(period)) return data.getMonthly();
        return data.getYearly();
    }

    private static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{8}")) throw invalidResponse();
        try {
            LocalDate parsed = LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
            if (parsed.getYear() < 1) throw invalidResponse();
            return parsed;
        } catch (DateTimeParseException e) {
            throw invalidResponse();
        }
    }

    private static String price(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return text;
        try {
            if (!text.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")) throw new NumberFormatException();
            // 방향 부호를 제거하되 원 단위 정수 문자열에 Integer 범위 제한을 두지 않습니다.
            return new BigDecimal(text).abs().toBigIntegerExact().toString();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalStateException("Kiwoom 기간별 차트 가격이 유효하지 않습니다.");
        }
    }

    private static IllegalStateException invalidResponse() {
        return new IllegalStateException("Kiwoom 기간별 차트 응답이 유효하지 않습니다.");
    }
}
