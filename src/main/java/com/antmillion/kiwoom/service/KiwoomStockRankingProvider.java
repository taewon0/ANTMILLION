package com.antmillion.kiwoom.service;

import com.antmillion.kiwoom.dto.KiwoomStockRankingRequest;
import com.antmillion.kiwoom.dto.KiwoomStockRankingResponse;
import com.antmillion.kiwoom.dto.KiwoomStockRankingResponse.Stock;
import com.antmillion.kiwoom.exception.KiwoomMarketDataException;
import com.antmillion.kiwoom.repository.KiwoomStockRankingRedisRepository;
import com.antmillion.marketdata.dto.RankedStock;
import com.antmillion.marketdata.provider.StockRankingProvider;
import com.antmillion.marketdata.resilience.MarketDataCircuitBreakers;
import com.antmillion.marketdata.resilience.MarketDataCircuitBreakers.Provider;
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

/** 인증 활성화 시 REST Provider로 등록하며 설정으로 선택합니다. */
public final class KiwoomStockRankingProvider implements StockRankingProvider {
    private final KiwoomTokenService tokens;
    private final RestTemplate rest;
    private final KiwoomRestRateLimiter limiter;
    private final KiwoomStockRankingRedisRepository cache;
    private final MarketDataCircuitBreakers circuitBreakers;

    public KiwoomStockRankingProvider(KiwoomTokenService tokens, RestTemplate rest,
            KiwoomRestRateLimiter limiter, KiwoomStockRankingRedisRepository cache,
            MarketDataCircuitBreakers circuitBreakers) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.rest = Objects.requireNonNull(rest, "rest");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.circuitBreakers = Objects.requireNonNull(circuitBreakers, "circuitBreakers");
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
        // 캐시 적중은 OPEN에서도 반환하며 탐색 허가와 성공 표본을 소비하지 않습니다.
        try (MarketDataCircuitBreakers.Call call = circuitBreakers.acquire(Provider.KIWOOM, tokens.getBaseUrl())) {
            limiter.acquireStockRanking(tokens);
            List<RankedStock> result = call.execute(() -> load(request));
            cache.save(tokens.getBaseUrl(), request, result);
            return result;
        }
    }

    private List<RankedStock> load(KiwoomStockRankingRequest request) {
        KiwoomStockRankingResponse response = fetch(request);
        if (response != null) {
            KiwoomMarketDataException.checkReturnCode(response.getReturnCode(), "Kiwoom 종목 순위 응답이 유효하지 않습니다.");
        }
        if (response == null || response.getStocks() == null) throw invalidResponse();
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
        return result;
    }

    private KiwoomStockRankingResponse fetch(KiwoomStockRankingRequest body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.set("api-id", "ka10030");
        try {
            headers.setBearerAuth(tokens.getAccessToken());
        } catch (RuntimeException e) {
            throw KiwoomMarketDataException.tokenAccess("Kiwoom 종목 순위 토큰 조회에 실패했습니다.");
        }
        try {
            return rest.postForObject(tokens.getBaseUrl() + "/api/dostk/rkinfo",
                    new HttpEntity<>(body, headers), KiwoomStockRankingResponse.class);
        } catch (RestClientException e) {
            throw KiwoomMarketDataException.fromHttp(e, "Kiwoom 종목 순위 HTTP 요청에 실패했습니다.");
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

    private static KiwoomMarketDataException invalidResponse() {
        return KiwoomMarketDataException.invalidResponse("Kiwoom 종목 순위 응답이 유효하지 않습니다.");
    }
}
