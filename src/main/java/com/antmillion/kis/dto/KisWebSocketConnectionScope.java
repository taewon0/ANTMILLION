package com.antmillion.kis.dto;

import com.antmillion.kis.constant.KisApiConstant;
import lombok.Getter;

import java.util.Objects;

/** 한 연결 시도의 approval 발급과 handshake가 함께 사용하는 불변 설정입니다. */
@Getter
public final class KisWebSocketConnectionScope {
    private final String baseUrl;
    private final String webSocketUrl;
    private final String appKey;
    private final String appSecret;

    public KisWebSocketConnectionScope(boolean real, String appKey, String appSecret) {
        this.baseUrl = real ? KisApiConstant.REAL_BASE_URL : KisApiConstant.VIRTUAL_BASE_URL;
        this.webSocketUrl = real ? KisApiConstant.REAL_WEB_SOCKET_URL : KisApiConstant.VIRTUAL_WEB_SOCKET_URL;
        this.appKey = Objects.requireNonNull(appKey, "appKey");
        this.appSecret = Objects.requireNonNull(appSecret, "appSecret");
    }
}
