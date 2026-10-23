package com.antmillion.kiwoom.repository;

import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 기존 Redis 직렬화 설정으로 저장할 JSON 문자열과 절대 만료 시각을 관리합니다. */
public class KiwoomAccessTokenRedisRepository {
    private final RedisTemplate<String, Object> redis;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public KiwoomAccessTokenRedisRepository(RedisTemplate<String, Object> redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public Optional<String> findAccessToken(KiwoomAccessTokenScope scope, long refreshBufferSeconds) {
        Object saved;
        try {
            saved = redis.opsForValue().get(scope.getTokenKey());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Kiwoom 토큰 저장소 조회에 실패했습니다.");
        }
        if (!(saved instanceof String)) return Optional.empty();
        try {
            JsonNode value = mapper.readTree((String) saved);
            if (value == null || !value.path("token").isTextual()
                    || value.path("token").asText().isBlank()
                    || value.path("token").asText().chars().anyMatch(Character::isWhitespace)
                    || !value.path("expires_at").isIntegralNumber()
                    || !value.path("expires_at").canConvertToLong()) return Optional.empty();
            Instant expiresAt = Instant.ofEpochMilli(value.path("expires_at").asLong());
            if (!expiresAt.isAfter(clock.instant().plusSeconds(refreshBufferSeconds))) return Optional.empty();
            return Optional.of(value.path("token").asText());
        } catch (JsonProcessingException e) {
            // 손상된 캐시는 재발급 대상으로 취급하며 원문을 노출하지 않습니다.
            return Optional.empty();
        }
    }

    public void save(KiwoomAccessTokenScope scope, String token, Instant expiresAt, long refreshBufferSeconds) {
        long ttlMillis = Duration.between(clock.instant(), expiresAt.minusSeconds(refreshBufferSeconds)).toMillis();
        if (ttlMillis <= 0) throw new IllegalStateException("Kiwoom 토큰의 남은 유효시간이 부족합니다.");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("token", token);
        value.put("expires_at", expiresAt.toEpochMilli());
        try {
            redis.opsForValue().set(scope.getTokenKey(), mapper.writeValueAsString(value), ttlMillis, TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalStateException("Kiwoom 토큰을 저장할 수 없습니다.");
        }
    }
}
