package com.antmillion.kiwoom.service;

import com.antmillion.kiwoom.dto.KiwoomMarketIndexChartRequest;
import com.antmillion.kiwoom.dto.KiwoomMarketIndexChartResponse;
import com.antmillion.kiwoom.dto.KiwoomMarketIndexChartResponse.Price;
import com.antmillion.kiwoom.repository.KiwoomMarketIndexChartRedisRepository;
import com.antmillion.marketdata.dto.MarketIndexCandle;
import com.antmillion.marketdata.dto.MarketIndexChartRequest;
import com.antmillion.marketdata.provider.MarketIndexChartProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 코스피·코스닥 내부 명시 호출 전용이며 Spring bean·운영 Provider로 등록하지 않습니다. */
public final class KiwoomMarketIndexChartProvider implements MarketIndexChartProvider {
    private final KiwoomTokenService tokens;
    private final RestTemplate rest;
    private final KiwoomRestRateLimiter limiter;
    private final KiwoomMarketIndexChartRedisRepository cache;

    public KiwoomMarketIndexChartProvider(KiwoomTokenService tokens, RestTemplate rest,
            KiwoomRestRateLimiter limiter, KiwoomMarketIndexChartRedisRepository cache) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.rest = Objects.requireNonNull(rest, "rest");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public List<MarketIndexCandle> getMarketIndexChart(MarketIndexChartRequest request) {
        if (request == null || request.getEndDate() == null || request.getEndDate().getYear() < 1
                || request.getEndDate().getYear() > 9999) {
            throw new IllegalArgumentException("Kiwoom 지수 일봉의 기준일이 유효하지 않습니다.");
        }
        String code = indexCode(request.getIndexCode());
        Optional<List<MarketIndexCandle>> cached = cache.find(tokens.getBaseUrl(), request);
        if (cached.isPresent()) return cached.get();
        KiwoomMarketIndexChartRequest body = new KiwoomMarketIndexChartRequest(code,
                request.getEndDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        // 다른 조회와 같은 singleton 예산을 사용하며 토큰 조회보다 먼저 거절합니다.
        limiter.acquireMarketIndexChart(tokens);
        KiwoomMarketIndexChartResponse data = fetch(body);
        if (data == null || (data.getReturnCode() != null && data.getReturnCode() != 0)
                || (data.getIndexCode() != null && !data.getIndexCode().isBlank()
                && !code.equals(data.getIndexCode())) || data.getPrices() == null) throw invalidResponse();
        List<MarketIndexCandle> result = new ArrayList<>();
        LocalDate previous = null;
        // 기존 KIS adapter와 같이 단일 응답을 반환하고 연속 조회·임의 건수 제한은 추가하지 않습니다.
        for (Price price : data.getPrices()) {
            if (price == null) {
                result.add(null);
                continue;
            }
            LocalDate date = date(price.getDate());
            if (date.isAfter(request.getEndDate()) || (previous != null && date.isAfter(previous))) {
                throw invalidResponse();
            }
            previous = date;
            result.add(new MarketIndexCandle(price.getDate(), point(price.getCloseValue()),
                    point(price.getOpenValue()), point(price.getHighValue()), point(price.getLowValue())));
        }
        cache.save(tokens.getBaseUrl(), request, result);
        return result;
    }

    private KiwoomMarketIndexChartResponse fetch(KiwoomMarketIndexChartRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.set("api-id", "ka20006");
        try {
            headers.setBearerAuth(tokens.getAccessToken());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Kiwoom 지수 일봉 토큰 조회에 실패했습니다.");
        }
        try {
            return rest.exchange(tokens.getBaseUrl() + "/api/dostk/chart", HttpMethod.POST,
                    new HttpEntity<>(body, headers), KiwoomMarketIndexChartResponse.class).getBody();
        } catch (RestClientException e) {
            throw new IllegalStateException("Kiwoom 지수 일봉 HTTP 요청에 실패했습니다.");
        }
    }

    private static String indexCode(String code) {
        if ("0001".equals(code)) return "001";
        if ("1001".equals(code)) return "101";
        throw new IllegalArgumentException("Kiwoom 지수 일봉은 코스피·코스닥만 지원합니다.");
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

    private static String point(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return text;
        if (!text.matches("[+-]?[0-9]+")) throw invalidResponse();
        // 공식 명세의 100배 정수와 방향 부호를 포인트 단위 소수 문자열로 변환합니다.
        return new BigDecimal(text).abs().movePointLeft(2).toPlainString();
    }

    private static IllegalStateException invalidResponse() {
        return new IllegalStateException("Kiwoom 지수 일봉 응답이 유효하지 않습니다.");
    }
}
