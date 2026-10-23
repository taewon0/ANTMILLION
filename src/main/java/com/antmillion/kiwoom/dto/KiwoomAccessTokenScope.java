package com.antmillion.kiwoom.dto;

import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 발급·저장·잠금에 동일하게 사용하는 불변 환경·자격증명입니다. */
@Getter
public final class KiwoomAccessTokenScope {
    private final String baseUrl;
    private final String appKey;
    private final String appSecret;
    private final String tokenKey;
    private final String lockKey;

    public KiwoomAccessTokenScope(String mode, String appKey, String appSecret) {
        if ("real".equalsIgnoreCase(mode)) {
            baseUrl = "https://api.kiwoom.com";
        } else if ("virtual".equalsIgnoreCase(mode)) {
            baseUrl = "https://mockapi.kiwoom.com";
        } else {
            throw new IllegalArgumentException("kiwoom.mode는 real 또는 virtual이어야 합니다.");
        }
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("Kiwoom 인증 자격증명이 필요합니다.");
        }
        this.appKey = appKey;
        this.appSecret = appSecret;
        String identity = identity();
        tokenKey = "kiwoom:access_token:v1:" + identity;
        lockKey = "kiwoom:token:lock:v1:" + identity;
    }

    private String identity() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{"kiwoom", baseUrl, appKey, appSecret}) {
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
