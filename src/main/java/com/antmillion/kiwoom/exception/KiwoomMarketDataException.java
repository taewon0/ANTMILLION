package com.antmillion.kiwoom.exception;

import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

/** 시세 실패의 안전한 분류만 보존하며 원격 본문·헤더·원인 예외를 보관하지 않습니다. */
public final class KiwoomMarketDataException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        AUTHENTICATION, REMOTE_RATE_LIMIT, SERVER, TRANSPORT, HTTP_ERROR,
        BUSINESS, INVALID_RESPONSE, TOKEN_ACCESS, CLIENT_ERROR
    }

    private final Kind kind;
    private final Integer httpStatus;
    private final Integer returnCode;

    private KiwoomMarketDataException(Kind kind, Integer httpStatus, Integer returnCode, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.returnCode = returnCode;
    }

    public Kind getKind() { return kind; }
    public Integer getHttpStatus() { return httpStatus; }
    public Integer getReturnCode() { return returnCode; }

    public static KiwoomMarketDataException fromHttp(RestClientException failure, String message) {
        if (failure instanceof RestClientResponseException) {
            int status = ((RestClientResponseException) failure).getRawStatusCode();
            Kind kind = status == 401 || status == 403 ? Kind.AUTHENTICATION
                    : status == 429 ? Kind.REMOTE_RATE_LIMIT
                    : status >= 500 && status <= 599 ? Kind.SERVER : Kind.HTTP_ERROR;
            return new KiwoomMarketDataException(kind, status, null, message);
        }
        if (failure instanceof ResourceAccessException) {
            return new KiwoomMarketDataException(Kind.TRANSPORT, null, null, message);
        }
        // RestTemplate은 JSON 변환 실패를 RestClientException으로 감싸므로 원인 타입만 검사합니다.
        if (failure instanceof UnknownContentTypeException
                || failure.contains(HttpMessageConversionException.class)) {
            return invalidResponse(message);
        }
        return new KiwoomMarketDataException(Kind.CLIENT_ERROR, null, null, message);
    }

    public static KiwoomMarketDataException tokenAccess(String message) {
        // Redis·잠금·발급 실패를 원격 인증 거절로 추정하지 않습니다.
        return new KiwoomMarketDataException(Kind.TOKEN_ACCESS, null, null, message);
    }

    public static KiwoomMarketDataException invalidResponse(String message) {
        return new KiwoomMarketDataException(Kind.INVALID_RESPONSE, null, null, message);
    }

    public static void checkReturnCode(Integer code, String message) {
        // 공급사 업무 코드를 HTTP 인증/제한 오류로 추정하지 않습니다.
        if (code != null && code != 0) {
            throw new KiwoomMarketDataException(Kind.BUSINESS, null, code, message);
        }
    }
}
