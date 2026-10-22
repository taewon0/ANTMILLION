package com.antmillion.kis.repository;

import com.antmillion.kis.dto.StockVolumeRank;
import com.antmillion.kis.dto.StockVolumeRankRequest;
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
public class KisStockVolumeRankRedisRepository {

    private static final String KIS_VOLUME_RANK = "kis:stock:volume-rank:v2";
    private static final long CACHE_EXPIRE = 60;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(String baseUrl, StockVolumeRankRequest request, List<StockVolumeRank> stockVolumeRanks) {
        redisTemplate.opsForValue().set(buildKey(baseUrl, request), stockVolumeRanks, CACHE_EXPIRE, TimeUnit.SECONDS);
    }

    public Optional<List<StockVolumeRank>> getStockVolumeRanks(String baseUrl, StockVolumeRankRequest request) {
        Object data =  redisTemplate.opsForValue().get(buildKey(baseUrl, request));

        if (data == null) {
            return Optional.empty();
        }

        try {
            List<StockVolumeRank> stockVolumeRanks = objectMapper.convertValue(
                    data,
                    new  TypeReference<List<StockVolumeRank>>() {}
            );
            return Optional.of(stockVolumeRanks);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Provider·실제 요청 환경·모든 조회 조건을 구분하며 기존 단일 키는 재사용하지 않습니다.
     */
    private String buildKey(String baseUrl, StockVolumeRankRequest request) {
        StringBuilder key = new StringBuilder(KIS_VOLUME_RANK);
        for (String value : new String[]{baseUrl, request.getMarketCode(), request.getScreenCode(),
                request.getInputCode(), request.getDivClassCode(), request.getBlngClassCode(),
                request.getTargetClassCode(), request.getTargetExlsClassCode(), request.getInputPrice1(),
                request.getInputPrice2(), request.getVolumeCount(), request.getInputDate1()}) {
            // 길이를 포함해 구분자·null·빈 문자열이 있는 조건도 서로 섞이지 않게 합니다.
            key.append(':').append(value == null ? -1 : value.length()).append(':');
            if (value != null) key.append(value);
        }
        return key.toString();
    }

}
