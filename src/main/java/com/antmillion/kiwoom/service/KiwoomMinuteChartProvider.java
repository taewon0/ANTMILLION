package com.antmillion.kiwoom.service;

import com.antmillion.kiwoom.dto.KiwoomMinuteChartRequest;
import com.antmillion.kiwoom.dto.KiwoomMinuteChartResponse;
import com.antmillion.kiwoom.dto.KiwoomMinuteChartResponse.Price;
import com.antmillion.kiwoom.exception.KiwoomMarketDataException;
import com.antmillion.kiwoom.repository.KiwoomDailyMinuteRedisRepository;
import com.antmillion.marketdata.dto.MinuteCandle;
import com.antmillion.marketdata.dto.MinuteChartRequest;
import com.antmillion.marketdata.dto.RecentMinuteCandle;
import com.antmillion.marketdata.provider.MinuteChartProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 인증 활성화 시 REST Provider로 등록하며 설정으로 선택합니다. */
public final class KiwoomMinuteChartProvider implements MinuteChartProvider {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private final KiwoomTokenService tokens;
    private final RestTemplate rest;
    private final KiwoomRestRateLimiter limiter;
    private final KiwoomDailyMinuteRedisRepository cache;
    private final Clock clock;

    public KiwoomMinuteChartProvider(KiwoomTokenService tokens, RestTemplate rest, KiwoomRestRateLimiter limiter,
            KiwoomDailyMinuteRedisRepository cache) {
        // 기존 Controller/서비스와 같은 JVM 기본 시간대를 유지합니다.
        this(tokens, rest, limiter, cache, Clock.systemDefaultZone());
    }

    public KiwoomMinuteChartProvider(KiwoomTokenService tokens, RestTemplate rest, KiwoomRestRateLimiter limiter,
            KiwoomDailyMinuteRedisRepository cache, Clock clock) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.rest = Objects.requireNonNull(rest, "rest");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<MinuteCandle> getDailyMinutes(MinuteChartRequest request) {
        if (request == null || request.getDate() == null || request.getDate().getYear() < 1
                || request.getDate().getYear() > 9999) {
            throw new IllegalArgumentException("Kiwoom 분봉 조회 날짜가 유효하지 않습니다.");
        }
        validateStockCode(request.getStockCode());
        Optional<List<MinuteCandle>> cached = cache.find(tokens.getBaseUrl(), request);
        if (cached.isPresent()) return cached.get();
        List<Price> prices = fetch(request.getStockCode(), request.getDate());
        if (prices == null) return Collections.emptyList();
        List<MinuteCandle> result = new ArrayList<>();
        for (Price price : select(prices, request.getDate().atTime(15, 30), 120, false)) {
            if (price == null) {
                result.add(null);
            } else {
                result.add(new MinuteCandle(price.getTimestamp().substring(0, 8),
                        price.getTimestamp().substring(8), price(price.getClosePrice()), price(price.getOpenPrice()),
                        price(price.getHighPrice()), price(price.getLowPrice())));
            }
        }
        Collections.reverse(result);
        cache.save(tokens.getBaseUrl(), request, result);
        return result;
    }

    @Override
    public List<RecentMinuteCandle> getRecentMinutes(String stockCode) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Price> prices = fetch(stockCode, now.toLocalDate());
        if (prices == null) return null;
        List<RecentMinuteCandle> result = new ArrayList<>();
        for (Price price : select(prices, now, 30, true)) {
            result.add(price == null ? null : new RecentMinuteCandle(price.getTimestamp().substring(8),
                    numericPrice(price.getClosePrice()), numericPrice(price.getOpenPrice()),
                    numericPrice(price.getHighPrice()), numericPrice(price.getLowPrice())));
        }
        return result;
    }

    private List<Price> fetch(String stockCode, LocalDate date) {
        validateStockCode(stockCode);
        KiwoomMinuteChartRequest body = new KiwoomMinuteChartRequest(stockCode,
                date.format(DateTimeFormatter.BASIC_ISO_DATE));
        // 두 분봉 경로는 같은 TR 예산을 소비하며 토큰 조회보다 먼저 거절합니다.
        limiter.acquireMinuteChart(tokens);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.set("api-id", "ka10080");
        try {
            headers.setBearerAuth(tokens.getAccessToken());
        } catch (RuntimeException e) {
            throw KiwoomMarketDataException.tokenAccess("Kiwoom 분봉 토큰 조회에 실패했습니다.");
        }
        KiwoomMinuteChartResponse data;
        try {
            // KIS와 같이 한 번만 조회하며 연속 조회 헤더가 있어도 추가 호출하지 않습니다.
            data = rest.exchange(tokens.getBaseUrl() + "/api/dostk/chart", HttpMethod.POST,
                    new HttpEntity<>(body, headers), KiwoomMinuteChartResponse.class).getBody();
        } catch (RestClientException e) {
            throw KiwoomMarketDataException.fromHttp(e, "Kiwoom 분봉 HTTP 요청에 실패했습니다.");
        }
        if (data != null) {
            KiwoomMarketDataException.checkReturnCode(data.getReturnCode(), "Kiwoom 분봉 응답이 유효하지 않습니다.");
        }
        if (data == null
                || (data.getStockCode() != null && !data.getStockCode().isBlank()
                && !stockCode.equals(data.getStockCode()))) throw invalidResponse();
        return data.getPrices();
    }

    private static void validateStockCode(String stockCode) {
        if (stockCode == null || !stockCode.matches("[0-9A-Z]{6}")) {
            throw new IllegalArgumentException("Kiwoom 분봉의 KRX 종목이 유효하지 않습니다.");
        }
    }

    private static List<Price> select(List<Price> prices, LocalDateTime end, int limit, boolean sameDateOnly) {
        List<Price> result = new ArrayList<>();
        LocalDateTime previous = null;
        for (Price price : prices) {
            if (result.size() == limit) break;
            if (price == null) {
                result.add(null);
                continue;
            }
            LocalDateTime time = timestamp(price.getTimestamp());
            if (previous != null && time.isAfter(previous)) throw invalidResponse();
            previous = time;
            // 일별은 실제 반환 날짜를 보존하며 각 날짜의 기준 시각·요청일 상한을 적용합니다.
            if (time.isAfter(end) || time.toLocalTime().isAfter(end.toLocalTime())) continue;
            // 날짜 필드가 없는 최근 분봉은 기존 당일 조건을 유지합니다.
            if (!sameDateOnly || time.toLocalDate().equals(end.toLocalDate())) result.add(price);
        }
        return result;
    }

    private static LocalDateTime timestamp(String value) {
        if (value == null || !value.matches("[0-9]{14}")) throw invalidResponse();
        try {
            LocalDateTime parsed = LocalDateTime.parse(value, TIMESTAMP);
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
            return new BigDecimal(text).abs().toBigIntegerExact().toString();
        } catch (ArithmeticException | NumberFormatException e) {
            throw invalidResponse();
        }
    }

    private static long numericPrice(String value) {
        String text = price(value);
        if (text == null || text.isEmpty()) return 0;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw invalidResponse();
        }
    }

    private static KiwoomMarketDataException invalidResponse() {
        return KiwoomMarketDataException.invalidResponse("Kiwoom 분봉 응답이 유효하지 않습니다.");
    }
}
