package com.antmillion.kis.dto;

import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** 토큰 조회부터 발급·저장까지 사용할 환경과 자격증명의 불변 스냅샷입니다. */
@Getter
public final class KisAccessTokenScope {
    private final String baseUrl;
    private final String appKey;
    private final String appSecret;
    private final String tokenKey;
    private final String lockKey;

    public KisAccessTokenScope(String baseUrl, String appKey, String appSecret) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.appKey = Objects.requireNonNull(appKey, "appKey");
        this.appSecret = Objects.requireNonNull(appSecret, "appSecret");
        String identity = identity();
        this.tokenKey = "kis:access_token:v2:" + identity;
        this.lockKey = "kis:token:lock:v2:" + identity;
    }

    private String identity() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 길이와 UTF-8 값을 함께 해시해 구분자가 포함된 값의 경계 혼동을 막습니다.
            for (String value : new String[]{"kis", baseUrl, appKey, appSecret}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value >>> 4) & 0xf, 16));
                result.append(Character.forDigit(value & 0xf, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("토큰 범위 식별자를 생성할 수 없습니다.", e);
        }
    }
}
