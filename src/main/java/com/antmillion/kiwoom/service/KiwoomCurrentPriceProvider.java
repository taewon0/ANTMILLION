package com.antmillion.kiwoom.service;

import com.antmillion.kiwoom.dto.KiwoomCurrentPriceRequest;
import com.antmillion.kiwoom.dto.KiwoomCurrentPriceResponse;
import com.antmillion.kiwoom.exception.KiwoomRateLimitExceededException;
import com.antmillion.kiwoom.repository.KiwoomCurrentPriceRedisRepository;
import com.antmillion.marketdata.dto.CurrentQuote;
import com.antmillion.marketdata.provider.CurrentPriceProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 내부 명시 호출 전용이며 Spring bean·운영 Provider로 등록하지 않습니다. */
public final class KiwoomCurrentPriceProvider implements CurrentPriceProvider {
    private final KiwoomTokenService tokens;
    private final RestTemplate rest;
    private final KiwoomRestRateLimiter limiter;
    private final KiwoomCurrentPriceRedisRepository cache;

    public KiwoomCurrentPriceProvider(KiwoomTokenService tokens, RestTemplate rest, KiwoomRestRateLimiter limiter,
                                      KiwoomCurrentPriceRedisRepository cache) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.rest = Objects.requireNonNull(rest, "rest");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public Integer getCurrentPrice(String stockCode) {
        validateStockCode(stockCode);
        try {
            limiter.acquireCurrentPrice(tokens);
        } catch (KiwoomRateLimitExceededException e) {
            return cache.findPrice(tokens.getBaseUrl(), stockCode).orElseThrow(() -> e);
        }
        String price = normalizePrice(fetch(stockCode).getCurrentPrice());
        Integer result = price == null || price.isEmpty() ? null : Integer.valueOf(price);
        cache.savePrice(tokens.getBaseUrl(), stockCode, result);
        return result;
    }

    @Override
    public List<CurrentQuote> getCurrentPrices(List<String> stockCodes) {
        Objects.requireNonNull(stockCodes, "stockCodes");
        List<CurrentQuote> quotes = new ArrayList<>();
        // 단건 API를 입력 순서대로 호출하며 실패한 종목만 제외합니다. 중복 입력도 보존합니다.
        for (String stockCode : stockCodes) {
            try {
                quotes.add(getCurrentQuote(stockCode));
            } catch (RuntimeException e) {
                // 원격 오류 본문·토큰·자격증명이 포함될 수 있어 예외 원문을 기록하지 않습니다.
            }
        }
        return quotes;
    }

    private CurrentQuote getCurrentQuote(String stockCode) {
        validateStockCode(stockCode);
        try {
            limiter.acquireCurrentPrice(tokens);
        } catch (KiwoomRateLimitExceededException e) {
            return cache.findQuote(tokens.getBaseUrl(), stockCode).orElseThrow(() -> e);
        }
        KiwoomCurrentPriceResponse response = fetch(stockCode);
        CurrentQuote result = new CurrentQuote(stockCode, normalizePrice(response.getCurrentPrice()),
                normalizeChangeRate(response.getChangeRate()));
        cache.saveQuote(tokens.getBaseUrl(), result);
        return result;
    }

    private void validateStockCode(String stockCode) {
        if (stockCode == null || !stockCode.matches("[0-9A-Z]{6}")) {
            throw new IllegalArgumentException("Kiwoom 현재가는 KRX 6자리 종목코드만 지원합니다.");
        }
    }

    private KiwoomCurrentPriceResponse fetch(String stockCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        try {
            headers.setBearerAuth(tokens.getAccessToken());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Kiwoom 현재가 토큰 조회에 실패했습니다.");
        }
        headers.set("api-id", "ka10001");
        KiwoomCurrentPriceResponse response;
        try {
            response = rest.postForObject(tokens.getBaseUrl() + "/api/dostk/stkinfo",
                    new HttpEntity<>(new KiwoomCurrentPriceRequest(stockCode), headers),
                    KiwoomCurrentPriceResponse.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("Kiwoom 현재가 HTTP 요청에 실패했습니다.");
        }
        if (response == null || (response.getReturnCode() != null && response.getReturnCode() != 0)) {
            throw new IllegalStateException("Kiwoom 현재가 응답이 유효하지 않습니다.");
        }
        if (response.getStockCode() != null && !response.getStockCode().isBlank()
                && !stockCode.equals(response.getStockCode())) {
            throw new IllegalStateException("Kiwoom 현재가 응답의 종목코드가 일치하지 않습니다.");
        }
        return response;
    }

    private static String normalizePrice(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return text;
        try {
            if (!text.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")) throw new NumberFormatException();
            // 키움 가격의 방향 부호를 제거하고 원 단위 정수·Integer 범위를 정확히 검사합니다.
            return Integer.toString(new BigDecimal(text).abs().intValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalStateException("Kiwoom 현재가 값이 유효하지 않습니다.");
        }
    }

    private static String normalizeChangeRate(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (!text.isEmpty() && !text.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")) {
            throw new IllegalStateException("Kiwoom 등락률 값이 유효하지 않습니다.");
        }
        return text;
    }
}
