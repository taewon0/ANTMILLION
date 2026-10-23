package com.antmillion.kiwoom.service;

import com.antmillion.kiwoom.dto.KiwoomAccessTokenRequest;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenResponse;
import com.antmillion.kiwoom.dto.KiwoomAccessTokenScope;
import com.antmillion.kiwoom.repository.KiwoomAccessTokenRedisRepository;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** 명시적 내부 호출 시에만 인증하며 스케줄러·HTTP 매핑·Provider 등록을 갖지 않습니다. */
public class KiwoomTokenService {
    private static final DateTimeFormatter EXPIRY_FORMAT = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private final KiwoomAccessTokenScope scope;
    private final long refreshBufferSeconds;
    private final RestTemplate rest;
    private final KiwoomAccessTokenRedisRepository tokens;
    private final RedissonClient locks;
    private final Clock clock;

    public KiwoomTokenService(KiwoomAccessTokenScope scope, long refreshBufferSeconds, RestTemplate rest,
                             KiwoomAccessTokenRedisRepository tokens, RedissonClient locks, Clock clock) {
        if (refreshBufferSeconds < 0 || refreshBufferSeconds > 43200) {
            throw new IllegalArgumentException("kiwoom.auth.refresh-buffer-seconds는 0~43200이어야 합니다.");
        }
        this.scope = scope;
        this.refreshBufferSeconds = refreshBufferSeconds;
        this.rest = rest;
        this.tokens = tokens;
        this.locks = locks;
        this.clock = clock;
    }

    public String getAccessToken() {
        Optional<String> saved = tokens.findAccessToken(scope, refreshBufferSeconds);
        if (saved.isPresent()) return saved.get();
        RLock lock = locks.getLock(scope.getLockKey());
        boolean acquired = false;
        try {
            // lease를 지정하지 않아 Redisson watchdog이 발급 작업 중 소유 잠금을 연장합니다.
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
            Optional<String> rechecked = tokens.findAccessToken(scope, refreshBufferSeconds);
            if (rechecked.isPresent()) return rechecked.get();
            if (!acquired) throw new IllegalStateException("Kiwoom 토큰 발급 잠금을 획득하지 못했습니다.");
            KiwoomAccessTokenResponse response = issueAccessToken();
            Instant expiresAt = parseExpiry(response);
            if (!expiresAt.isAfter(clock.instant().plusSeconds(refreshBufferSeconds))) {
                throw new IllegalStateException("Kiwoom 토큰의 남은 유효시간이 부족합니다.");
            }
            if (!lock.isHeldByCurrentThread()) {
                throw new IllegalStateException("Kiwoom 토큰 발급 잠금의 소유권을 잃었습니다.");
            }
            tokens.save(scope, response.getToken(), expiresAt, refreshBufferSeconds);
            return response.getToken();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kiwoom 토큰 발급 대기가 중단되었습니다.");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private KiwoomAccessTokenResponse issueAccessToken() {
        KiwoomAccessTokenRequest body = new KiwoomAccessTokenRequest(scope.getAppKey(), scope.getAppSecret());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        KiwoomAccessTokenResponse response;
        try {
            response = rest.postForObject(scope.getBaseUrl() + "/oauth2/token",
                    new HttpEntity<>(body, headers), KiwoomAccessTokenResponse.class);
        } catch (RestClientException e) {
            // 원격 오류 본문·파싱 예외에는 토큰이나 자격증명이 들어갈 수 있습니다.
            throw new IllegalStateException("Kiwoom 토큰 발급 HTTP 요청에 실패했습니다.");
        }
        if (response == null || (response.getReturnCode() != null && response.getReturnCode() != 0)
                || response.getToken() == null || response.getToken().isBlank()
                || response.getToken().chars().anyMatch(Character::isWhitespace)
                || !"bearer".equalsIgnoreCase(response.getTokenType())) {
            throw new IllegalStateException("Kiwoom 토큰 발급 응답이 유효하지 않습니다.");
        }
        return response;
    }

    private Instant parseExpiry(KiwoomAccessTokenResponse response) {
        try {
            if (response.getExpiresDt() == null || !response.getExpiresDt().matches("[0-9]{14}")) {
                throw new IllegalStateException("Kiwoom 토큰 만료 시각이 유효하지 않습니다.");
            }
            return LocalDateTime.parse(response.getExpiresDt(), EXPIRY_FORMAT).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("Kiwoom 토큰 만료 시각이 유효하지 않습니다.");
        }
    }
}
