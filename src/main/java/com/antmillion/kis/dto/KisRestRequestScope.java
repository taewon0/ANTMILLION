package com.antmillion.kis.dto;

import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 시세 REST 한 번의 예산·토큰·헤더·주소에 사용하는 불변 스냅샷입니다. */
@Getter
public final class KisRestRequestScope {
    private final KisAccessTokenScope tokenScope;
    private final String budgetKey;

    public KisRestRequestScope(String baseUrl, String accountNo, String productCode,
                               String appKey, String appSecret) {
        tokenScope = new KisAccessTokenScope(baseUrl, appKey, appSecret);
        budgetKey = identity(baseUrl, required(accountNo), required(productCode));
    }

    public String getBaseUrl() {
        return tokenScope.getBaseUrl();
    }

    private static String required(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("KIS 호출 제한에 계좌번호와 상품코드가 필요합니다.");
        }
        return value.trim();
    }

    private static String identity(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("kis:rest:".getBytes(StandardCharsets.UTF_8));
            for (String value : values) {
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
            throw new IllegalStateException("KIS 호출 예산 식별자를 생성할 수 없습니다.", e);
        }
    }
}
