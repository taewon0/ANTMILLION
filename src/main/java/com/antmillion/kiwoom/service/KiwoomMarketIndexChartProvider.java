package com.antmillion.kiwoom.service;

import com.antmillion.kiwoom.dto.KiwoomMarketIndexChartRequest;
import com.antmillion.kiwoom.dto.KiwoomMarketIndexChartResponse;
import com.antmillion.kiwoom.dto.KiwoomMarketIndexChartResponse.Price;
import com.antmillion.kiwoom.exception.KiwoomMarketDataException;
import com.antmillion.kiwoom.repository.KiwoomMarketIndexChartRedisRepository;
import com.antmillion.marketdata.dto.MarketIndexCandle;
import com.antmillion.marketdata.dto.MarketIndexChartRequest;
import com.antmillion.marketdata.provider.MarketIndexChartProvider;
import com.antmillion.marketdata.resilience.MarketDataCircuitBreakers;
import com.antmillion.marketdata.resilience.MarketDataCircuitBreakers.Provider;
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

/** 코스피·코스닥 인증 활성화 시 REST Provider로 등록하며 설정으로 선택합니다. */
public final class KiwoomMarketIndexChartProvider implements MarketIndexChartProvider {
    private static final int MAX_CANDLES = 100;
    private final KiwoomTokenService tokens;
    private final RestTemplate rest;
    private final KiwoomRestRateLimiter limiter;
    private final KiwoomMarketIndexChartRedisRepository cache;
    private final MarketDataCircuitBreakers circuitBreakers;

    public KiwoomMarketIndexChartProvider(KiwoomTokenService tokens, RestTemplate rest,
            KiwoomRestRateLimiter limiter, KiwoomMarketIndexChartRedisRepository cache,
            MarketDataCircuitBreakers circuitBreakers) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.rest = Objects.requireNonNull(rest, "rest");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.circuitBreakers = Objects.requireNonNull(circuitBreakers, "circuitBreakers");
    }

    @Override
    public List<MarketIndexCandle> getMarketIndexChart(MarketIndexChartRequest request) {
        if (request == null || request.getEndDate() == null || request.getEndDate().getYear() < 1
                || request.getEndDate().getYear() > 9999) {
            throw new IllegalArgumentException("Kiwoom 지수 일봉의 기준일이 유효하지 않습니다.");
        }
        String code = indexCode(request.getIndexCode());
        Optional<List<MarketIndexCandle>> cached = cache.find(tokens.getBaseUrl(), request);
        if (cached.isPresent()) return newestCandles(cached.get());
        // 캐시 적중은 OPEN에서도 반환하며 탐색 허가와 성공 표본을 소비하지 않습니다.
        try (MarketDataCircuitBreakers.Call call = circuitBreakers.acquire(Provider.KIWOOM, tokens.getBaseUrl())) {
            limiter.acquireMarketIndexChart(tokens);
            List<MarketIndexCandle> result = call.execute(() -> load(request, code));
            cache.save(tokens.getBaseUrl(), request, result);
            return result;
        }
    }

    private List<MarketIndexCandle> load(MarketIndexChartRequest request, String code) {
        KiwoomMarketIndexChartRequest body = new KiwoomMarketIndexChartRequest(code,
                request.getEndDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        KiwoomMarketIndexChartResponse data = fetch(body);
        if (data != null) {
            KiwoomMarketDataException.checkReturnCode(data.getReturnCode(), "Kiwoom 지수 일봉 응답이 유효하지 않습니다.");
        }
        if (data == null
                || (data.getIndexCode() != null && !data.getIndexCode().isBlank()
                && !code.equals(data.getIndexCode())) || data.getPrices() == null) throw invalidResponse();
        List<MarketIndexCandle> result = new ArrayList<>();
        LocalDate previous = null;
        // 단일 응답 전체를 검증한 뒤 최신 100개만 반환하며 연속 조회는 하지 않습니다.
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
        return newestCandles(result);
    }

    private static List<MarketIndexCandle> newestCandles(List<MarketIndexCandle> candles) {
        // 이전 버전의 큰 캐시에도 같은 상한을 적용하고 null 행·중복·공급 순서를 보존합니다.
        return new ArrayList<>(candles.subList(0, Math.min(MAX_CANDLES, candles.size())));
    }

    private KiwoomMarketIndexChartResponse fetch(KiwoomMarketIndexChartRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.set("api-id", "ka20006");
        try {
            headers.setBearerAuth(tokens.getAccessToken());
        } catch (RuntimeException e) {
            throw KiwoomMarketDataException.tokenAccess("Kiwoom 지수 일봉 토큰 조회에 실패했습니다.");
        }
        try {
            return rest.exchange(tokens.getBaseUrl() + "/api/dostk/chart", HttpMethod.POST,
                    new HttpEntity<>(body, headers), KiwoomMarketIndexChartResponse.class).getBody();
        } catch (RestClientException e) {
            throw KiwoomMarketDataException.fromHttp(e, "Kiwoom 지수 일봉 HTTP 요청에 실패했습니다.");
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

    private static KiwoomMarketDataException invalidResponse() {
        return KiwoomMarketDataException.invalidResponse("Kiwoom 지수 일봉 응답이 유효하지 않습니다.");
    }
}
