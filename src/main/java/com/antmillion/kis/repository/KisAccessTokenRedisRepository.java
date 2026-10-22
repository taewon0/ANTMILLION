package com.antmillion.kis.repository;

import com.antmillion.kis.dto.KisAccessTokenScope;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
public class KisAccessTokenRedisRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Redis에 액세스 토큰 저장
     * @param scope 발급에 사용한 환경·자격증명 범위
     * @param token 액세스 토큰
     * @param expiresInSeconds 만료 시간(초)
     */
    public void save(KisAccessTokenScope scope, String token, long expiresInSeconds) {
        redisTemplate.opsForValue().set(scope.getTokenKey(), token, expiresInSeconds, TimeUnit.SECONDS);
    }

    /**
     * Redis에서 액세스 토큰 조회
     * @param scope 조회할 환경·자격증명 범위
     * @return 저장된 토큰 (없거나 만료되면 Empty)
     */
    public Optional<String> findAccessToken(KisAccessTokenScope scope) {
        Object token = redisTemplate.opsForValue().get(scope.getTokenKey());
        if (token instanceof String) {
            return Optional.of((String) token);
        }
        return Optional.empty();
    }

    /**
     * Redis에서 액세스 토큰 삭제
     * @param scope 삭제할 환경·자격증명 범위
     */
    public void deleteAccessToken(KisAccessTokenScope scope) {
        redisTemplate.delete(scope.getTokenKey());
    }
}
