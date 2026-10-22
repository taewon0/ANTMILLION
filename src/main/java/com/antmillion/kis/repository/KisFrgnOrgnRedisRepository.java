package com.antmillion.kis.repository;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import com.antmillion.kis.dto.ForeignerOrganization;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class KisFrgnOrgnRedisRepository {
	private static final String KIS_FRGNORGN_NTBY = "kis:frgnorgn-ntby:v2";
    private static final long CACHE_EXPIRE = 600;

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(String baseUrl, String stockCode, ForeignerOrganization data) {
        String key = buildKey(baseUrl, stockCode);
        redisTemplate.opsForValue().set(key, data, CACHE_EXPIRE, TimeUnit.SECONDS);
    }

    public Optional<ForeignerOrganization> getKisFrgnorgnNtby(String baseUrl, String stockCode) {
        String key = buildKey(baseUrl, stockCode);
        Object data =  redisTemplate.opsForValue().get(key);

        if (data == null) {
            return Optional.empty();
        }

        try {
        	ForeignerOrganization foreignerOrganization = objectMapper.convertValue(
                    data,
                    new  TypeReference<ForeignerOrganization>() {}
            );
            return Optional.of(foreignerOrganization);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Provider·실제 요청 환경·종목을 구분하며 기존 키는 재사용하지 않습니다.
     */
    private String buildKey(String baseUrl, String stockCode) {
        StringBuilder key = new StringBuilder(KIS_FRGNORGN_NTBY);
        for (String value : new String[]{baseUrl, stockCode}) {
            // 길이를 포함해 구분자·null·빈 문자열이 있는 조건도 서로 섞이지 않게 합니다.
            key.append(':').append(value == null ? -1 : value.length()).append(':');
            if (value != null) key.append(value);
        }
        return key.toString();
    }
}
