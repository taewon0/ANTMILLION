package com.antmillion.kiwoom.service;

import com.antmillion.kiwoom.dto.KiwoomStockRankingRequest;
import com.antmillion.kiwoom.dto.KiwoomStockRankingResponse;
import com.antmillion.kiwoom.dto.KiwoomStockRankingResponse.Stock;
import com.antmillion.kiwoom.repository.KiwoomStockRankingRedisRepository;
import com.antmillion.marketdata.dto.RankedStock;
import com.antmillion.marketdata.provider.StockRankingProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 내부 명시 호출 전용이며 Spring bean·운영 Provider로 등록하지 않습니다. */
public final class KiwoomStockRankingProvider implements StockRankingProvider {
    private final KiwoomTokenService tokens;
    private final RestTemplate rest;
    private final KiwoomRestRateLimiter limiter;
    private final KiwoomStockRankingRedisRepository cache;

    public KiwoomStockRankingProvider(KiwoomTokenService tokens, RestTemplate rest,
            KiwoomRestRateLimiter limiter, KiwoomStockRankingRedisRepository cache) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.rest = Objects.requireNonNull(rest, "rest");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public List<RankedStock> getTradingValueRanks() {
        return getRanks(false);
    }

    @Override
    public List<RankedStock> getVolumeRanks() {
        // 사용자 합의: KIS의 평균거래량과 달리 Kiwoom의 당일거래량을 사용합니다.
        return getRanks(true);
    }

    private List<RankedStock> getRanks(boolean volumeOrder) {
        KiwoomStockRankingRequest request = new KiwoomStockRankingRequest(volumeOrder);
        Optional<List<RankedStock>> cached = cache.find(tokens.getBaseUrl(), request);
        if (cached.isPresent()) return cached.get();
        // 다른 조회와 같은 singleton 예산을 사용하며 토큰 조회보다 먼저 거절합니다.
        limiter.acquireStockRanking(tokens);
        KiwoomStockRankingResponse response = fetch(request);
        if (response == null || (response.getReturnCode() != null && response.getReturnCode() != 0)
                || response.getStocks() == null) throw invalidResponse();
        List<RankedStock> result = new ArrayList<>();
        // 기존 KIS 단일 조회의 최대 30건에 맞추며 연속 조회·재정렬은 하지 않습니다.
        for (int i = 0; i < Math.min(30, response.getStocks().size()); i++) {
            Stock stock = response.getStocks().get(i);
            if (stock == null) {
                result.add(null);
                continue;
            }
            String code = stock.getStockCode();
            if (code != null && !code.isEmpty() && !code.matches("[0-9A-Z]{6}")) throw invalidResponse();
            // ka10030에는 순위/관심 필드가 없어 응답 위치 순위와 기존 KIS의 관심 기본값을 사용합니다.
            result.add(new RankedStock(stock.getStockName(), code, Integer.toString(i + 1),
                    integer(stock.getCurrentPrice(), true), integer(stock.getAccumulatedVolume(), false),
                    rate(stock.getChangeRate()), false));
        }
        cache.save(tokens.getBaseUrl(), request, result);
        return result;
    }

    private KiwoomStockRankingResponse fetch(KiwoomStockRankingRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.set("api-id", "ka10030");
        try {
            headers.setBearerAuth(tokens.getAccessToken());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Kiwoom 종목 순위 토큰 조회에 실패했습니다.");
        }
        try {
            return rest.postForObject(tokens.getBaseUrl() + "/api/dostk/rkinfo",
                    new HttpEntity<>(body, headers), KiwoomStockRankingResponse.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("Kiwoom 종목 순위 HTTP 요청에 실패했습니다.");
        }
    }

    private static String integer(String value, boolean price) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return text;
        if (!text.matches(price ? "[+-]?[0-9]+" : "[+]?[0-9]+")) throw invalidResponse();
        // 가격의 방향 부호만 제거하며 큰 거래량도 좁은 정수 타입으로 절삭하지 않습니다.
        return new BigInteger(text).abs().toString();
    }

    private static String rate(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (!text.isEmpty() && !text.matches("[+-]?[0-9]+(?:\\.[0-9]+)?")) throw invalidResponse();
        return text;
    }

    private static IllegalStateException invalidResponse() {
        return new IllegalStateException("Kiwoom 종목 순위 응답이 유효하지 않습니다.");
    }
}
