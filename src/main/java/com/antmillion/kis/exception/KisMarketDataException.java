package com.antmillion.kis.exception;

import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

/** 시세 오류의 분류·숫자 코드만 보존하며 원격 본문·헤더·원인 예외는 보관하지 않습니다. */
public final class KisMarketDataException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        AUTHENTICATION, REMOTE_RATE_LIMIT, SERVER, TRANSPORT, HTTP_ERROR,
        BUSINESS, INVALID_RESPONSE, TOKEN_ACCESS, CLIENT_ERROR
    }

    private final Kind kind;
    private final Integer httpStatus;
    private final Integer returnCode;

    private KisMarketDataException(Kind kind, Integer httpStatus, Integer returnCode, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.returnCode = returnCode;
    }

    public Kind getKind() { return kind; }
    public Integer getHttpStatus() { return httpStatus; }
    public Integer getReturnCode() { return returnCode; }

    public static KisMarketDataException fromHttp(RestClientException failure, String message) {
        if (failure instanceof RestClientResponseException) {
            int status = ((RestClientResponseException) failure).getRawStatusCode();
            Kind kind = status == 401 || status == 403 ? Kind.AUTHENTICATION
                    : status == 429 ? Kind.REMOTE_RATE_LIMIT
                    : status >= 500 && status <= 599 ? Kind.SERVER : Kind.HTTP_ERROR;
            return new KisMarketDataException(kind, status, null, message);
        }
        if (failure instanceof ResourceAccessException) {
            return new KisMarketDataException(Kind.TRANSPORT, null, null, message);
        }
        if (failure instanceof UnknownContentTypeException
                || failure.contains(HttpMessageConversionException.class)) {
            return invalidResponse(message);
        }
        return new KisMarketDataException(Kind.CLIENT_ERROR, null, null, message);
    }

    public static KisMarketDataException tokenAccess(String message) {
        // 저장소·잠금·발급 실패를 시세 HTTP의 인증 거절로 추정하지 않습니다.
        return new KisMarketDataException(Kind.TOKEN_ACCESS, null, null, message);
    }

    public static KisMarketDataException invalidResponse(String message) {
        return new KisMarketDataException(Kind.INVALID_RESPONSE, null, null, message);
    }

    public static void checkReturnCode(String code, String message) {
        // 기존 코드 누락/빈 값은 허용합니다. 업무 코드를 HTTP 상태나 재시도 가능성으로 해석하지 않습니다.
        if (code == null || code.isBlank() || "0".equals(code)) return;
        Integer numericCode = null;
        if (code.matches("-?[0-9]{1,10}")) {
            try {
                numericCode = Integer.valueOf(code);
            } catch (NumberFormatException e) {
                // 범위를 넘거나 숫자가 아닌 원격 문자열은 예외에 보관하지 않습니다.
            }
        }
        throw new KisMarketDataException(Kind.BUSINESS, null, numericCode, message);
    }
}
