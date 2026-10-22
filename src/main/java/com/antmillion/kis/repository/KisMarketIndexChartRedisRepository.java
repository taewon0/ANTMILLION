package com.antmillion.kis.repository;

import com.antmillion.kis.dto.MarketIndexPrice;
import com.antmillion.kis.dto.MarketIndexPriceRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
public class KisMarketIndexChartRedisRepository {

    private static final String KIS_MARKET_INDEX_CHART_KEY = "kis:chart:market-index:v2";
    private static final long CACHE_EXPIRE = 60;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(String baseUrl, MarketIndexPriceRequest request, List<MarketIndexPrice> chartData) {
        String key = buildKey(baseUrl, request);
        redisTemplate.opsForValue().set(key, chartData, CACHE_EXPIRE, TimeUnit.SECONDS);
    }

    public Optional<List<MarketIndexPrice>> getChartData(String baseUrl, MarketIndexPriceRequest request) {
        String key = buildKey(baseUrl, request);
        Object data = redisTemplate.opsForValue().get(key);

        if (data == null) {
            return Optional.empty();
        }

        try {
            List<MarketIndexPrice> chartData = objectMapper.convertValue(
                    data,
                    new  TypeReference<List<MarketIndexPrice>>() {}
            );
            return Optional.of(chartData);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Provider·실제 요청 환경·모든 조회 조건을 구분하며 기존 키는 재사용하지 않습니다.
     */
    private String buildKey(String baseUrl, MarketIndexPriceRequest request) {
        StringBuilder key = new StringBuilder(KIS_MARKET_INDEX_CHART_KEY);
        for (String value : new String[]{baseUrl, request.getPeriodCode(), request.getMarketCode(),
                request.getIndexCode(), request.getEndDate()}) {
            // 길이를 포함해 구분자·null·빈 문자열이 있는 조건도 서로 섞이지 않게 합니다.
            key.append(':').append(value == null ? -1 : value.length()).append(':');
            if (value != null) key.append(value);
        }
        return key.toString();
    }

}
