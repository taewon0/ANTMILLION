package com.antmillion.kis.repository;

import com.antmillion.kis.dto.ChartStockPrice;
import com.antmillion.kis.dto.ChartStockPriceRequest;
import com.antmillion.kis.dto.StreamMinutePrice;
import com.antmillion.kis.dto.StreamMinutePriceRequest;
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
public class KisChartRedisRepository {

    private static final String KIS_PERIOD_CHART_KEY = "kis:chart:period:v2";
    private static final String KIS_STREAM_MINUTE_CHART_KEY = "kis:chart:stream-minute:v2";
    private static final long CACHE_EXPIRE_HOURS = 24; // 24시간 캐시 유지
    private static final long CACHE_EXPIRE_MINUTE = 1; // 1분

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    /***
     * Redis에 차트 데이터 저장
     * @param baseUrl 실제 요청 대상 주소(실전/모의 환경 구분)
     * @param request 전체 기간별 조회 조건
     * @param chartData 차트 데이터
     */
    public void save(String baseUrl, ChartStockPriceRequest request, List<ChartStockPrice> chartData) {
        String key = buildKey(baseUrl, request);
        redisTemplate.opsForValue().set(key, chartData, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    public Optional<List<ChartStockPrice>> getChartData(String baseUrl, ChartStockPriceRequest request) {
        String key = buildKey(baseUrl, request);
        Object data = redisTemplate.opsForValue().get(key);

        if (data == null) {
            return Optional.empty();
        }

        try {
            // Redis에서 가져온 데이터를 List<ChartStockPrice>로 변환
            List<ChartStockPrice> chartData = objectMapper.convertValue(
                    data,
                    new TypeReference<List<ChartStockPrice>>() {}
            );
            return Optional.of(chartData);
        } catch (Exception e) {
            return Optional.empty();
        }

    }

    /**
     * Provider·환경·모든 조회 조건을 구분하며, 불완전한 기존 키는 재사용하지 않습니다.
     */
    private String buildKey(String baseUrl, ChartStockPriceRequest request) {
        StringBuilder key = new StringBuilder(KIS_PERIOD_CHART_KEY);
        for (String value : new String[]{baseUrl, request.getMarketCode(), request.getStockCode(),
                request.getPeriodCode(), request.getStartDate(), request.getEndDate(), request.getAdjPrice()}) {
            // 길이를 함께 저장해 구분자가 포함된 값과 null/빈 문자열도 서로 섞이지 않게 합니다.
            key.append(':').append(value == null ? -1 : value.length()).append(':');
            if (value != null) key.append(value);
        }
        return key.toString();
    }


    /**
     * Redis에 일별 분봉 데이터 저장
     */
    public void saveStreamMinute(String baseUrl, StreamMinutePriceRequest request, List<StreamMinutePrice> streamData) {
        String key = buildStreamMinuteKey(baseUrl, request);
        redisTemplate.opsForValue().set(key, streamData, CACHE_EXPIRE_MINUTE, TimeUnit.MINUTES); // TTL 1분
    }
    
    /**
     * 일별 분봉 데이터 조회
     */
    public Optional<List<StreamMinutePrice>> getStreamMinuteChartData(String baseUrl, StreamMinutePriceRequest request) {
        String key = buildStreamMinuteKey(baseUrl, request);
        Object data = redisTemplate.opsForValue().get(key);

        if (data == null) return Optional.empty();

        try {
            List<StreamMinutePrice> chartData = objectMapper.convertValue(
                    data,
                    new TypeReference<List<StreamMinutePrice>>() {}
            );
            return Optional.of(chartData);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
    
    /**
     * Provider·실제 요청 환경·모든 조회 조건을 구분하고 기존 키는 재사용하지 않습니다.
     */
    private String buildStreamMinuteKey(String baseUrl, StreamMinutePriceRequest request) {
        StringBuilder key = new StringBuilder(KIS_STREAM_MINUTE_CHART_KEY);
        for (String value : new String[]{baseUrl, request.getCondMrktDivCode(), request.getInputIscd(),
                request.getInputHour1(), request.getInputDate1(), request.getPwDataIncuYn(), request.getFakeTickIncuYn()}) {
            // 길이를 함께 저장해 구분자가 포함된 값과 null/빈 문자열도 서로 섞이지 않게 합니다.
            key.append(':').append(value == null ? -1 : value.length()).append(':');
            if (value != null) key.append(value);
        }
        return key.toString();
    }

}
